



















use alloc::alloc::{alloc, alloc_zeroed, dealloc, Layout};
use core::ffi::{c_int, c_void};

const USIZE_ALIGN: usize = core::mem::align_of::<usize>();
const USIZE_SIZE: usize = core::mem::size_of::<usize>();

#[no_mangle]
pub extern "C" fn rust_lz4_wasm_shim_malloc(size: usize) -> *mut c_void {
    wasm_shim_alloc::<false>(size)
}

#[no_mangle]
pub extern "C" fn rust_lz4_wasm_shim_memcmp(
    str1: *const c_void,
    str2: *const c_void,
    n: usize,
) -> i32 {

    unsafe {
        let str1: &[u8] = core::slice::from_raw_parts(str1 as *const u8, n);
        let str2: &[u8] = core::slice::from_raw_parts(str2 as *const u8, n);
        match str1.cmp(str2) {
            core::cmp::Ordering::Less => -1,
            core::cmp::Ordering::Equal => 0,
            core::cmp::Ordering::Greater => 1,
        }
    }
}

#[no_mangle]
pub extern "C" fn rust_lz4_wasm_shim_calloc(nmemb: usize, size: usize) -> *mut c_void {

    wasm_shim_alloc::<true>(nmemb * size)
}

#[inline]
fn wasm_shim_alloc<const ZEROED: bool>(size: usize) -> *mut c_void {





    let full_alloc_size = size + USIZE_SIZE;

    unsafe {
        let layout = Layout::from_size_align_unchecked(full_alloc_size, USIZE_ALIGN);

        let ptr = if ZEROED {
            alloc_zeroed(layout)
        } else {
            alloc(layout)
        };


        ptr.cast::<usize>().write(full_alloc_size);

        ptr.add(USIZE_SIZE).cast()
    }
}

#[no_mangle]
pub unsafe extern "C" fn rust_lz4_wasm_shim_free(ptr: *mut c_void) {




    let alloc_ptr = ptr.sub(USIZE_SIZE);

    let full_alloc_size = alloc_ptr.cast::<usize>().read();

    let layout = Layout::from_size_align_unchecked(full_alloc_size, USIZE_ALIGN);
    dealloc(alloc_ptr.cast(), layout);
}

#[no_mangle]
pub unsafe extern "C" fn rust_lz4_wasm_shim_memcpy(
    dest: *mut c_void,
    src: *const c_void,
    n: usize,
) -> *mut c_void {
    core::ptr::copy_nonoverlapping(src as *const u8, dest as *mut u8, n);
    dest
}

#[no_mangle]
pub unsafe extern "C" fn rust_lz4_wasm_shim_memmove(
    dest: *mut c_void,
    src: *const c_void,
    n: usize,
) -> *mut c_void {
    core::ptr::copy(src as *const u8, dest as *mut u8, n);
    dest
}

#[no_mangle]
pub unsafe extern "C" fn rust_lz4_wasm_shim_memset(
    dest: *mut c_void,
    c: c_int,
    n: usize,
) -> *mut c_void {
    core::ptr::write_bytes(dest as *mut u8, c as u8, n);
    dest
}
