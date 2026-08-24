



use proc_macro2::{Span, TokenStream};
use quote::ToTokens;
use std::cell::RefCell;


#[derive(Default)]
pub struct Errors {
    errors: RefCell<Vec<syn::Error>>,
}


macro_rules! expect_lit_fn {
    ($(($fn_name:ident, $syn_type:ident, $variant:ident, $lit_name:literal),)*) => {
        $(
            pub fn $fn_name<'a>(&self, e: &'a syn::Expr) -> Option<&'a syn::$syn_type> {
                if let syn::Expr::Lit(syn::ExprLit { lit: syn::Lit::$variant(inner), .. }) = e {
                    Some(inner)
                } else {
                    self.unexpected_lit($lit_name, e);
                    None
                }
            }
        )*
    }
}


macro_rules! expect_meta_fn {
    ($(($fn_name:ident, $syn_type:ident, $variant:ident, $meta_name:literal),)*) => {
        $(
            pub fn $fn_name<'a>(&self, meta: &'a syn::Meta) -> Option<&'a syn::$syn_type> {
                if let syn::Meta::$variant(inner) = meta {
                    Some(inner)
                } else {
                    self.unexpected_meta($meta_name, meta);
                    None
                }
            }
        )*
    }
}

impl Errors {




    pub fn duplicate_attrs(
        &self,
        attr_kind: &str,
        first: &impl syn::spanned::Spanned,
        second: &impl syn::spanned::Spanned,
    ) {
        self.duplicate_attrs_inner(attr_kind, first.span(), second.span())
    }

    fn duplicate_attrs_inner(&self, attr_kind: &str, first: Span, second: Span) {
        self.err_span(second, &["Duplicate ", attr_kind, " attribute"].concat());
        self.err_span(first, &["First ", attr_kind, " attribute here"].concat());
    }

    expect_lit_fn![
        (expect_lit_str, LitStr, Str, "string"),
        (expect_lit_char, LitChar, Char, "character"),
        (expect_lit_int, LitInt, Int, "integer"),
    ];

    expect_meta_fn![
        (expect_meta_word, Path, Path, "path"),
        (expect_meta_list, MetaList, List, "list"),
        (
            expect_meta_name_value,
            MetaNameValue,
            NameValue,
            "name-value pair"
        ),
    ];

    fn unexpected_lit(&self, expected: &str, found: &syn::Expr) {
        fn lit_kind(lit: &syn::Lit) -> &'static str {
            use syn::Lit::{Bool, Byte, ByteStr, Char, Float, Int, Str, Verbatim};
            match lit {
                Str(_) => "string",
                ByteStr(_) => "bytestring",
                Byte(_) => "byte",
                Char(_) => "character",
                Int(_) => "integer",
                Float(_) => "float",
                Bool(_) => "boolean",
                Verbatim(_) => "unknown (possibly extra-large integer)",
                _ => "unknown literal kind",
            }
        }

        if let syn::Expr::Lit(syn::ExprLit { lit, .. }) = found {
            self.err(
                found,
                &[
                    "Expected ",
                    expected,
                    " literal, found ",
                    lit_kind(lit),
                    " literal",
                ]
                .concat(),
            )
        } else {
            self.err(
                found,
                &[
                    "Expected ",
                    expected,
                    " literal, found non-literal expression.",
                ]
                .concat(),
            )
        }
    }

    fn unexpected_meta(&self, expected: &str, found: &syn::Meta) {
        fn meta_kind(meta: &syn::Meta) -> &'static str {
            use syn::Meta::{List, NameValue, Path};
            match meta {
                Path(_) => "path",
                List(_) => "list",
                NameValue(_) => "name-value pair",
            }
        }

        self.err(
            found,
            &[
                "Expected ",
                expected,
                " attribute, found ",
                meta_kind(found),
                " attribute",
            ]
            .concat(),
        )
    }


    pub fn err(&self, spanned: &impl syn::spanned::Spanned, msg: &str) {
        self.err_span(spanned.span(), msg);
    }


    pub fn err_span(&self, span: Span, msg: &str) {
        self.push(syn::Error::new(span, msg));
    }


    pub fn err_span_tokens<T: ToTokens>(&self, tokens: T, msg: &str) {
        self.push(syn::Error::new_spanned(tokens, msg));
    }


    pub fn push(&self, err: syn::Error) {
        self.errors.borrow_mut().push(err);
    }


    pub fn ok<T>(&self, r: syn::Result<T>) -> Option<T> {
        match r {
            Ok(v) => Some(v),
            Err(e) => {
                self.push(e);
                None
            }
        }
    }
}

impl ToTokens for Errors {


    fn to_tokens(&self, tokens: &mut TokenStream) {
        tokens.extend(self.errors.borrow().iter().map(|e| e.to_compile_error()));
    }
}
