




























































































































































































































































































































use std::str::FromStr;

pub use derive::FromArgs;


pub type CommandInfo = argh_shared::CommandInfo<'static>;


pub type CommandInfoWithArgs = argh_shared::CommandInfoWithArgs<'static>;


pub type SubCommandInfo = argh_shared::SubCommandInfo<'static>;

pub use argh_shared::{ErrorCodeInfo, FlagInfo, FlagInfoKind, Optionality, PositionalInfo};


pub trait ArgsInfo {

    fn get_args_info() -> CommandInfoWithArgs;


    fn get_subcommands() -> Vec<SubCommandInfo> {
        Self::get_args_info().commands
    }
}


pub trait FromArgs: Sized {
























































































































    fn from_args(command_name: &[&str], args: &[&str]) -> Result<Self, EarlyExit>;
}


pub trait TopLevelCommand: FromArgs {}


pub trait SubCommands: FromArgs {

    const COMMANDS: &'static [&'static CommandInfo];


    fn dynamic_commands() -> &'static [&'static CommandInfo] {
        &[]
    }
}


pub trait SubCommand: FromArgs {

    const COMMAND: &'static CommandInfo;
}

impl<T: SubCommand> SubCommands for T {
    const COMMANDS: &'static [&'static CommandInfo] = &[T::COMMAND];
}


pub trait DynamicSubCommand: Sized {

    fn commands() -> &'static [&'static CommandInfo];









    fn try_redact_arg_values(
        command_name: &[&str],
        args: &[&str],
    ) -> Option<Result<Vec<String>, EarlyExit>>;








    fn try_from_args(command_name: &[&str], args: &[&str]) -> Option<Result<Self, EarlyExit>>;
}




#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EarlyExit {

    pub output: String,

    pub is_help: bool,
}

impl From<String> for EarlyExit {
    fn from(err_msg: String) -> Self {
        Self {
            output: err_msg,
            is_help: false,
        }
    }
}







pub trait FromArgValue: Sized {


    fn from_arg_value(value: &str) -> Result<Self, String>;
}

impl<T> FromArgValue for T
where
    T: FromStr,
    T::Err: std::fmt::Display,
{
    fn from_arg_value(value: &str) -> Result<Self, String> {
        T::from_str(value).map_err(|x| x.to_string())
    }
}




#[doc(hidden)]
pub trait ParseFlag {
    fn set_flag(&mut self, arg: &str);
}

impl<T: Flag> ParseFlag for T {
    fn set_flag(&mut self, _arg: &str) {
        <T as Flag>::set_flag(self);
    }
}







#[doc(hidden)]
pub trait ParseValueSlot {
    fn fill_slot(&mut self, arg: &str, value: &str) -> Result<(), String>;
}





#[doc(hidden)]
pub struct ParseValueSlotTy<Slot, T> {

    pub slot: Slot,

    pub parse_func: fn(&str, &str) -> Result<T, String>,
}



impl<T> ParseValueSlot for ParseValueSlotTy<Option<T>, T> {
    fn fill_slot(&mut self, arg: &str, value: &str) -> Result<(), String> {
        if self.slot.is_some() {
            return Err("duplicate values provided".to_string());
        }
        self.slot = Some((self.parse_func)(arg, value)?);
        Ok(())
    }
}


impl<T> ParseValueSlot for ParseValueSlotTy<Vec<T>, T> {
    fn fill_slot(&mut self, arg: &str, value: &str) -> Result<(), String> {
        self.slot.push((self.parse_func)(arg, value)?);
        Ok(())
    }
}


impl<T> ParseValueSlot for ParseValueSlotTy<Option<Vec<T>>, T> {
    fn fill_slot(&mut self, arg: &str, value: &str) -> Result<(), String> {
        self.slot
            .get_or_insert_with(Vec::new)
            .push((self.parse_func)(arg, value)?);
        Ok(())
    }
}


pub trait Flag {

    fn default() -> Self
    where
        Self: Sized;


    fn set_flag(&mut self);
}

impl Flag for bool {
    fn default() -> Self {
        false
    }
    fn set_flag(&mut self) {
        *self = true;
    }
}

impl Flag for Option<bool> {
    fn default() -> Self {
        None
    }

    fn set_flag(&mut self) {
        *self = Some(true);
    }
}

macro_rules! impl_flag_for_integers {
    ($($ty:ty,)*) => {
        $(
            impl Flag for $ty {
                fn default() -> Self {
                    0
                }
                fn set_flag(&mut self) {
                    *self = self.saturating_add(1);
                }
            }
        )*
    }
}

impl_flag_for_integers![u8, u16, u32, u64, u128, i8, i16, i32, i64, i128,];









#[doc(hidden)]
pub fn parse_struct_args(
    cmd_name: &[&str],
    args: &[&str],
    mut parse_options: ParseStructOptions<'_>,
    mut parse_positionals: ParseStructPositionals<'_>,
    mut parse_subcommand: Option<ParseStructSubCommand<'_>>,
) -> Result<(), EarlyExit> {
    let mut help = false;
    let mut remaining_args = args;
    let mut positional_index = 0;
    let mut options_ended = false;

    'parse_args: while let Some(&next_arg) = remaining_args.first() {
        remaining_args = &remaining_args[1..];
        if (parse_options.help_triggers.contains(&next_arg)) && !options_ended {
            help = true;
            continue;
        }

        if next_arg.starts_with('-') && !options_ended {
            if next_arg == "--" {
                options_ended = true;
                continue;
            }

            if help {
                return Err("Trailing arguments are not allowed after `help`."
                    .to_string()
                    .into());
            }

            parse_options.parse(next_arg, &mut remaining_args)?;
            continue;
        }

        if let Some(ref mut parse_subcommand) = parse_subcommand
            && parse_subcommand.parse(help, cmd_name, next_arg, remaining_args)?
        {

            help = false;
            break 'parse_args;
        }

        options_ended |= parse_positionals.parse(&mut positional_index, next_arg)?;
    }

    if help {
        Err(EarlyExit {
            output: String::new(),
            is_help: true,
        })
    } else {
        Ok(())
    }
}

#[doc(hidden)]
pub struct ParseStructOptions<'a> {




    pub arg_to_slot: &'static [(&'static str, usize)],


    pub slots: &'a mut [ParseStructOption<'a>],


    pub help_triggers: &'a [&'a str],
}

impl<'a> ParseStructOptions<'a> {





    fn parse(&mut self, arg: &str, remaining_args: &mut &[&str]) -> Result<(), String> {
        let pos = self
            .arg_to_slot
            .iter()
            .find_map(|&(name, pos)| if name == arg { Some(pos) } else { None })
            .ok_or_else(|| unrecognized_argument(arg, self.arg_to_slot, self.help_triggers))?;

        match self.slots[pos] {
            ParseStructOption::Flag(ref mut b) => b.set_flag(arg),
            ParseStructOption::Value(ref mut pvs) => {
                let value = remaining_args
                    .first()
                    .ok_or_else(|| ["No value provided for option '", arg, "'.\n"].concat())?;
                *remaining_args = &remaining_args[1..];
                pvs.fill_slot(arg, value).map_err(|s| {
                    [
                        "Error parsing option '",
                        arg,
                        "' with value '",
                        value,
                        "': ",
                        &s,
                        "\n",
                    ]
                    .concat()
                })?;
            }
        }

        Ok(())
    }
}

fn unrecognized_argument(
    given: &str,
    arg_to_slot: &[(&str, usize)],
    extra_suggestions: &[&str],
) -> String {

    let available = arg_to_slot
        .iter()
        .map(|(name, _pos)| *name)
        .chain(extra_suggestions.iter().copied())
        .collect::<Vec<&str>>();

    if available.is_empty() {
        return format!("Unrecognized argument: \"{}\"\n", given);
    }

    ["Unrecognized argument: ", given, "\n"].concat()
}


#[doc(hidden)]
pub enum ParseStructOption<'a> {

    Flag(&'a mut dyn ParseFlag),


    Value(&'a mut dyn ParseValueSlot),
}

#[doc(hidden)]
pub struct ParseStructPositionals<'a> {
    pub positionals: &'a mut [ParseStructPositional<'a>],
    pub last_is_repeating: bool,
    pub last_is_greedy: bool,
}

impl ParseStructPositionals<'_> {






    fn parse(&mut self, index: &mut usize, arg: &str) -> Result<bool, EarlyExit> {
        if *index < self.positionals.len() {
            self.positionals[*index].parse(arg)?;

            if self.last_is_repeating && *index == self.positionals.len() - 1 {



                Ok(self.last_is_greedy)
            } else {


                *index += 1;
                Ok(false)
            }
        } else {
            Err(EarlyExit {
                output: unrecognized_arg(arg),
                is_help: false,
            })
        }
    }
}

#[doc(hidden)]
pub struct ParseStructPositional<'a> {

    pub name: &'static str,


    pub slot: &'a mut dyn ParseValueSlot,
}

impl ParseStructPositional<'_> {



    fn parse(&mut self, arg: &str) -> Result<(), EarlyExit> {
        self.slot.fill_slot("", arg).map_err(|s| {
            [
                "Error parsing positional argument '",
                self.name,
                "' with value '",
                arg,
                "': ",
                &s,
                "\n",
            ]
            .concat()
            .into()
        })
    }
}





#[doc(hidden)]
pub struct ParseStructSubCommand<'a> {

    pub subcommands: &'static [&'static CommandInfo],

    pub dynamic_subcommands: &'a [&'static CommandInfo],


    #[allow(clippy::type_complexity)]
    pub parse_func: &'a mut dyn FnMut(&[&str], &[&str]) -> Result<(), EarlyExit>,
}

impl ParseStructSubCommand<'_> {
    fn parse(
        &mut self,
        help: bool,
        cmd_name: &[&str],
        arg: &str,
        remaining_args: &[&str],
    ) -> Result<bool, EarlyExit> {
        for subcommand in self
            .subcommands
            .iter()
            .chain(self.dynamic_subcommands.iter())
        {
            if subcommand.name == arg {
                let mut command = cmd_name.to_owned();
                command.push(subcommand.name);
                let prepended_help;
                let remaining_args = if help {
                    prepended_help = prepend_help(remaining_args);
                    &prepended_help
                } else {
                    remaining_args
                };

                (self.parse_func)(&command, remaining_args)?;

                return Ok(true);
            }
        }

        Ok(false)
    }
}



fn prepend_help<'a>(args: &[&'a str]) -> Vec<&'a str> {
    [&["help"], args].concat()
}

#[doc(hidden)]
pub fn print_subcommands<'a>(commands: impl Iterator<Item = &'a CommandInfo>) -> String {
    let mut out = String::new();
    for cmd in commands {
        argh_shared::write_description(&mut out, cmd);
    }
    out
}

fn unrecognized_arg(arg: &str) -> String {
    ["Unrecognized argument: ", arg, "\n"].concat()
}


#[doc(hidden)]
#[derive(Default)]
pub struct MissingRequirements {
    options: Vec<&'static str>,
    subcommands: Option<Vec<&'static CommandInfo>>,
    positional_args: Vec<&'static str>,
}

const NEWLINE_INDENT: &str = "\n    ";

impl MissingRequirements {

    #[doc(hidden)]
    pub fn missing_option(&mut self, name: &'static str) {
        self.options.push(name)
    }


    #[doc(hidden)]
    pub fn missing_subcommands(&mut self, commands: impl Iterator<Item = &'static CommandInfo>) {
        self.subcommands = Some(commands.collect());
    }


    #[doc(hidden)]
    pub fn missing_positional_arg(&mut self, name: &'static str) {
        self.positional_args.push(name)
    }



    #[doc(hidden)]
    pub fn err_on_any(&self) -> Result<(), String> {
        if self.options.is_empty() && self.subcommands.is_none() && self.positional_args.is_empty()
        {
            return Ok(());
        }

        let mut output = String::new();

        if !self.positional_args.is_empty() {
            output.push_str("Required positional arguments not provided:");
            for arg in &self.positional_args {
                output.push_str(NEWLINE_INDENT);
                output.push_str(arg);
            }
        }

        if !self.options.is_empty() {
            if !self.positional_args.is_empty() {
                output.push('\n');
            }
            output.push_str("Required options not provided:");
            for option in &self.options {
                output.push_str(NEWLINE_INDENT);
                output.push_str(option);
            }
        }

        if let Some(missing_subcommands) = &self.subcommands {
            if !self.options.is_empty() {
                output.push('\n');
            }
            output.push_str("One of the following subcommands must be present:");
            output.push_str(NEWLINE_INDENT);
            output.push_str("help");
            for subcommand in missing_subcommands {
                output.push_str(NEWLINE_INDENT);
                output.push_str(subcommand.name);
            }
        }

        output.push('\n');

        Err(output)
    }
}

mod argh_shared {





    pub struct CommandInfo<'a> {

        pub name: &'a str,

        pub description: &'a str,
    }


    #[derive(Debug, Default, PartialEq, Eq, Clone)]
    pub struct CommandInfoWithArgs<'a> {

        pub name: &'a str,

        pub description: &'a str,

        pub examples: &'a [&'a str],

        pub flags: &'a [FlagInfo<'a>],

        pub notes: &'a [&'a str],

        pub commands: Vec<SubCommandInfo<'a>>,

        pub positionals: &'a [PositionalInfo<'a>],

        pub error_codes: &'a [ErrorCodeInfo<'a>],
    }


    #[derive(Debug, PartialEq, Eq)]
    pub struct ErrorCodeInfo<'a> {

        pub code: i32,

        pub description: &'a str,
    }


    #[derive(Debug, PartialEq, Eq)]
    pub struct PositionalInfo<'a> {

        pub name: &'a str,

        pub description: &'a str,

        pub optionality: Optionality,



        pub hidden: bool,
    }





    #[derive(Debug, Default, PartialEq, Eq, Clone)]
    pub struct SubCommandInfo<'a> {

        pub name: &'a str,

        pub command: CommandInfoWithArgs<'a>,
    }


    #[derive(Debug, Default, PartialEq, Eq)]
    pub struct FlagInfo<'a> {

        pub kind: FlagInfoKind<'a>,

        pub optionality: Optionality,

        pub long: &'a str,


        pub short: Option<char>,

        pub description: &'a str,



        pub hidden: bool,
    }


    #[derive(Debug, Default, PartialEq, Eq)]
    pub enum FlagInfoKind<'a> {

        #[default]
        Switch,


        Option { arg_name: &'a str },
    }



    #[derive(Debug, Default, PartialEq, Eq)]
    pub enum Optionality {


        #[default]
        Required,


        Optional,


        Repeating,



        Greedy,
    }

    pub const INDENT: &str = "  ";
    const DESCRIPTION_INDENT: usize = 20;
    const WRAP_WIDTH: usize = 80;


    pub fn write_description(out: &mut String, cmd: &CommandInfo<'_>) {
        let mut current_line = INDENT.to_string();
        current_line.push_str(cmd.name);

        if cmd.description.is_empty() {
            new_line(&mut current_line, out);
            return;
        }

        if !indent_description(&mut current_line) {


            new_line(&mut current_line, out);
        }

        let mut words = cmd.description.split(' ').peekable();
        while let Some(first_word) = words.next() {
            indent_description(&mut current_line);
            current_line.push_str(first_word);

            'inner: while let Some(&word) = words.peek() {
                if (char_len(&current_line) + char_len(word) + 1) > WRAP_WIDTH {
                    new_line(&mut current_line, out);
                    break 'inner;
                } else {

                    let _ = words.next();
                    current_line.push(' ');
                    current_line.push_str(word);
                }
            }
        }
        new_line(&mut current_line, out);
    }



    fn indent_description(line: &mut String) -> bool {
        let cur_len = char_len(line);
        if cur_len < DESCRIPTION_INDENT {
            let num_spaces = DESCRIPTION_INDENT - cur_len;
            line.extend(std::iter::repeat_n(' ', num_spaces));
            true
        } else {
            false
        }
    }

    fn char_len(s: &str) -> usize {
        s.chars().count()
    }



    fn new_line(current_line: &mut String, out: &mut String) {
        out.push('\n');
        out.push_str(current_line);
        current_line.truncate(0);
    }
}
