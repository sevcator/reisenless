use proc_macro2::TokenStream;
use quote::{quote, quote_spanned};
use syn::spanned::Spanned;
use syn::{Data, DeriveInput, Fields, GenericParam, parse_macro_input, parse_quote};

pub(crate) fn derive_decodable(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let input = parse_macro_input!(input as DeriveInput);

    let name = input.ident;


    let mut generics = input.generics;
    for param in &mut generics.params {
        if let GenericParam::Type(ref mut type_param) = *param {
            type_param
                .bounds
                .push(parse_quote!(crate::socket::Decodable));
        }
    }

    let (impl_generics, ty_generics, where_clause) = generics.split_for_impl();

    let encode = gen_encode(&input.data);
    let decode = gen_decode(&input.data);

    let expanded = quote! {

        impl #impl_generics crate::socket::Encodable for #name #ty_generics #where_clause {
            fn encode(&self, w: &mut impl std::io::Write) -> std::io::Result<()> {
                #encode
                Ok(())
            }
        }
        impl #impl_generics crate::socket::Decodable for #name #ty_generics #where_clause {
            fn decode(r: &mut impl std::io::Read) -> std::io::Result<Self> {
                let val = #decode;
                Ok(val)
            }
        }
    };
    proc_macro::TokenStream::from(expanded)
}


fn gen_encode(data: &Data) -> TokenStream {
    match *data {
        Data::Struct(ref data) => {
            match data.fields {
                Fields::Named(ref fields) => {



                    let recurse = fields.named.iter().map(|f| {
                        let name = &f.ident;
                        quote_spanned! { f.span() =>
                            crate::socket::Encodable::encode(&self.#name, w)?;
                        }
                    });
                    quote! {
                        #(#recurse)*
                    }
                }
                _ => unimplemented!(),
            }
        }
        Data::Enum(_) | Data::Union(_) => unimplemented!(),
    }
}


fn gen_decode(data: &Data) -> TokenStream {
    match *data {
        Data::Struct(ref data) => {
            match data.fields {
                Fields::Named(ref fields) => {



                    let recurse = fields.named.iter().map(|f| {
                        let name = &f.ident;
                        quote_spanned! { f.span() =>
                            #name: crate::socket::Decodable::decode(r)?,
                        }
                    });
                    quote! {
                        Self { #(#recurse)* }
                    }
                }
                _ => unimplemented!(),
            }
        }
        Data::Enum(_) | Data::Union(_) => unimplemented!(),
    }
}
