use argh::FromArgs;
use base::{LoggedResult, MappedFile, Utf8CStr, argh};
use fdt::nodes::{Node, NodeProperty};
use fdt::parsing::Panic;
use fdt::parsing::unaligned::UnalignedParser;
use fdt::{Fdt, FdtError, FdtHeader};

use crate::check_env;
use crate::patch::patch_verity;

type UnalignedFdt<'a> = Fdt<'a, (UnalignedParser<'a>, Panic)>;
type UnalignedNode<'a> = Node<'a, (UnalignedParser<'a>, Panic)>;

#[derive(FromArgs)]
#[argh(subcommand)]
pub(crate) enum DtbAction {
    Print(Print),
    Patch(Patch),
    Test(Test),
}

#[derive(FromArgs)]
#[argh(subcommand, name = "print")]
pub(crate) struct Print {
    #[argh(switch, short = 'f', long = none)]
    fstab: bool,
}

#[derive(FromArgs)]
#[argh(subcommand, name = "patch")]
pub(crate) struct Patch {}

#[derive(FromArgs)]
#[argh(subcommand, name = "test")]
pub(crate) struct Test {}

pub(crate) fn print_dtb_usage() {
    eprintln!(
        r#"Usage: mboot dtb <file> <action> [args...]
Do dtb related actions to <file>.

Supported actions:
  print [-f]
    Print all contents of dtb for debugging
    Specify [-f] to only print fstab nodes
  patch
    Search for fstab and remove verity/avb
    Modifications are done directly to the file in-place
    Configure with env variables: KEEPVERITY
  test
    Test the fstab's status
    Return values:
    0:valid    1:error"#
    );
}

const MAX_PRINT_LEN: usize = 32;

fn print_node(node: &UnalignedNode) {
    fn pretty_node(depth_set: &[bool]) {
        let mut depth_set = depth_set.iter().peekable();
        while let Some(depth) = depth_set.next() {
            let last = depth_set.peek().is_none();
            if *depth {
                if last {
                    print!("├── ");
                } else {
                    print!("│   ");
                }
            } else if last {
                print!("└── ");
            } else {
                print!("    ");
            }
        }
    }

    fn pretty_prop(depth_set: &[bool]) {
        let mut depth_set = depth_set.iter().peekable();
        while let Some(depth) = depth_set.next() {
            let last = depth_set.peek().is_none();
            if *depth {
                if last {
                    print!("│  ");
                } else {
                    print!("│   ");
                }
            } else if last {
                print!("└─ ");
            } else {
                print!("    ");
            }
        }
    }

    fn do_print_node(node: &UnalignedNode, depth_set: &mut Vec<bool>) {
        pretty_node(depth_set);
        let depth = depth_set.len();
        depth_set.push(true);
        println!("{}", node.name().name);
        let mut properties = node.properties().iter().peekable();
        let mut children = node.children().iter().peekable();
        while let Some(NodeProperty { name, value }) = properties.next() {
            let size = value.len();
            let is_str = !(size > 1 && value[0] == 0)
                && matches!(value.last(), Some(0u8) | None)
                && value.iter().all(|c| *c == 0 || (*c >= 32 && *c < 127));

            if depth_set[depth] && properties.peek().is_none() && children.peek().is_none() {
                depth_set[depth] = false;
            }

            pretty_prop(depth_set);
            if is_str {
                println!(
                    "[{}]: [\"{}\"]",
                    name,
                    if value.is_empty() {
                        ""
                    } else {
                        unsafe { Utf8CStr::from_bytes_unchecked(value) }
                    }
                );
            } else if size > MAX_PRINT_LEN {
                println!("[{name}]: <bytes>({size})");
            } else {
                println!("[{name}]: {value:02x?}");
            }
        }

        while let Some(child) = children.next() {
            if depth_set[depth] && children.peek().is_none() {
                depth_set[depth] = false;
            }
            do_print_node(&child, depth_set);
        }
        depth_set.pop();
    }

    do_print_node(node, &mut vec![]);
}

const DTB_MAGIC: &[u8] = b"\xd0\x0d\xfe\xed";

const MIN_NON_EMPTY_DTB_SIZE: usize = 0x84;

pub(crate) fn find_dtb_offset(buf: &[u8]) -> Option<usize> {
    let mut pos = 0;
    while pos + size_of::<FdtHeader>() <= buf.len() {
        let rel_pos = buf[pos..].windows(4).position(|w| w == DTB_MAGIC)?;
        let curr = pos + rel_pos;
        let sub = &buf[curr..];

        let Ok(fdt) = Fdt::new_unaligned_fallible(sub) else {
            pos = curr + 4;
            continue;
        };

        if fdt.total_size() <= MIN_NON_EMPTY_DTB_SIZE
            || fdt.find_node("/").ok().flatten().is_none()
        {
            pos = curr + 4;
            continue;
        }

        return Some(curr);
    }
    None
}

pub(crate) fn find_dtb_offset_for_cxx(buf: &[u8]) -> i32 {
    find_dtb_offset(buf).map_or(-1, |v| v as i32)
}

fn for_each_fdt<F: FnMut(usize, UnalignedFdt) -> LoggedResult<()>>(
    file: &Utf8CStr,
    rw: bool,
    mut f: F,
) -> LoggedResult<()> {
    eprintln!("Loading dtbs from [{file}]");
    let file = if rw {
        MappedFile::open_rw(file)?
    } else {
        MappedFile::open(file)?
    };
    let mut buf = Some(file.as_ref());
    let mut dtb_num = 0usize;
    while let Some(slice) = buf {
        let slice = if let Some(pos) = find_dtb_offset(slice) {
            &slice[pos..]
        } else {
            break;
        };
        let fdt = match Fdt::new_unaligned(slice) {
            Err(FdtError::SliceTooSmall) => {
                eprintln!("dtb.{dtb_num:04} is truncated");
                break;
            }
            Ok(fdt) => fdt,
            e => e?,
        };

        let size = fdt.total_size();

        f(dtb_num, fdt)?;

        dtb_num += 1;
        buf = Some(&slice[size..]);
    }
    Ok(())
}

fn find_fstab<'a>(fdt: &UnalignedFdt<'a>) -> Option<UnalignedNode<'a>> {
    fdt.all_nodes()
        .find_map(|(_, node)| (node.name().name == "fstab").then_some(node))
}

fn dtb_print(file: &Utf8CStr, fstab: bool) -> LoggedResult<()> {
    for_each_fdt(file, false, |n, fdt| {
        if fstab {
            if let Some(fstab) = find_fstab(&fdt) {
                eprintln!("Found fstab in dtb.{n:04}");
                print_node(&fstab);
            }
        } else if let Some(root) = fdt.find_node("/") {
            eprintln!("Printing dtb.{n:04}");
            print_node(&root);
        }
        Ok(())
    })
}

fn dtb_test(file: &Utf8CStr) -> LoggedResult<bool> {
    let mut ret = true;
    for_each_fdt(file, false, |_, fdt| {
        if let Some(fstab) = find_fstab(&fdt) {
            for child in fstab.children().iter() {
                if child.name().name != "system" {
                    continue;
                }
                if let Some(mount_point) = child.raw_property("mnt_point")
                    && mount_point.value == b"/system_root\0"
                {
                    ret = false;
                    break;
                }
            }
        }
        Ok(())
    })?;
    Ok(ret)
}

fn dtb_patch(file: &Utf8CStr) -> LoggedResult<bool> {
    let keep_verity = check_env("KEEPVERITY");
    let mut patched = false;
    eprintln!("Loading dtbs from [{file}]");
    let mut mapped = MappedFile::open_rw(file)?;
    let base_ptr = mapped.as_mut().as_mut_ptr();
    let total_len = mapped.as_ref().len();
    let mut offset = 0usize;
    let mut dtb_num = 0usize;

    while offset < total_len {
        let slice = &mapped.as_ref()[offset..];
        let pos = match find_dtb_offset(slice) {
            Some(pos) => pos,
            None => break,
        };
        let dtb_offset = offset + pos;
        let dtb_slice = &mapped.as_ref()[dtb_offset..];
        let fdt = match Fdt::new_unaligned(dtb_slice) {
            Err(FdtError::SliceTooSmall) => {
                eprintln!("dtb.{dtb_num:04} is truncated");
                break;
            }
            Ok(fdt) => fdt,
            e => e?,
        };
        let size = fdt.total_size();

        for (_, node) in fdt.all_nodes() {
            if node.name().name != "chosen" {
                continue;
            }
            if let Some(boot_args) = node.raw_property("bootargs") {
                boot_args.value.windows(14).for_each(|w| {
                    if w == b"skip_initramfs" {
                        let rel = (w.as_ptr() as usize) - (base_ptr as usize);
                        if rel + 4 <= total_len {
                            unsafe {
                                std::ptr::copy_nonoverlapping(
                                    b"want".as_ptr(),
                                    base_ptr.add(rel),
                                    4,
                                );
                            }
                            eprintln!("Patch [skip_initramfs] -> [want_initramfs] in dtb.{dtb_num:04}");
                            patched = true;
                        }
                    }
                });
            }
        }
        if !keep_verity && let Some(fstab) = find_fstab(&fdt) {
            for child in fstab.children().iter() {
                if let Some(flags) = child.raw_property("fsmgr_flags") {
                    let rel = (flags.value.as_ptr() as usize) - (base_ptr as usize);
                    let flags_len = flags.value.len();
                    if rel + flags_len <= total_len {
                        let target_slice = unsafe {
                            std::slice::from_raw_parts_mut(base_ptr.add(rel), flags_len)
                        };
                        if patch_verity(target_slice) != flags_len {
                            patched = true;
                        }
                    }
                }
            }
        }

        dtb_num += 1;
        offset = dtb_offset + size;
    }
    Ok(patched)
}

pub(crate) fn dtb_commands(file: &Utf8CStr, action: &DtbAction) -> LoggedResult<bool> {
    match action {
        DtbAction::Print(Print { fstab }) => {
            dtb_print(file, *fstab)?;
            Ok(true)
        }
        DtbAction::Test(_) => Ok(dtb_test(file)?),
        DtbAction::Patch(_) => Ok(dtb_patch(file)?),
    }
}
