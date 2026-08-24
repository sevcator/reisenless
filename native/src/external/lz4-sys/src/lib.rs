#![allow(unexpected_cfgs)]
#![no_std]
extern crate libc;

#[cfg(not(all(
    target_arch = "wasm32",
    not(any(target_env = "wasi", target_os = "wasi"))
)))]
pub use libc::{c_char, c_int, c_uint, c_ulonglong, c_void, size_t};

#[cfg(all(
    target_arch = "wasm32",
    not(any(target_env = "wasi", target_os = "wasi"))
))]
extern crate alloc;

#[cfg(all(
    target_arch = "wasm32",
    not(any(target_env = "wasi", target_os = "wasi"))
))]
mod wasm_shim;

#[cfg(all(
    target_arch = "wasm32",
    not(any(target_env = "wasi", target_os = "wasi"))
))]
extern crate std;

#[cfg(all(
    target_arch = "wasm32",
    not(any(target_env = "wasi", target_os = "wasi"))
))]
pub use std::os::raw::{c_char, c_int, c_uint, c_ulonglong, c_void};

#[cfg(all(
    target_arch = "wasm32",
    not(any(target_env = "wasi", target_os = "wasi"))
))]
#[allow(non_camel_case_types)]
pub type size_t = usize;

#[derive(Clone, Copy, Debug)]
#[repr(C)]
pub struct LZ4FCompressionContext(pub *mut c_void);
unsafe impl Send for LZ4FCompressionContext {}

#[derive(Clone, Copy, Debug)]
#[repr(C)]
pub struct LZ4FDecompressionContext(pub *mut c_void);
unsafe impl Send for LZ4FDecompressionContext {}

pub type LZ4FErrorCode = size_t;

#[derive(Clone, Debug)]
#[repr(u32)]
pub enum BlockSize {
    Default = 0,
    Max64KB = 4,
    Max256KB = 5,
    Max1MB = 6,
    Max4MB = 7,
}

impl BlockSize {
    pub fn get_size(&self) -> usize {
        match self {
            &BlockSize::Default | &BlockSize::Max64KB => 64 * 1024,
            &BlockSize::Max256KB => 256 * 1024,
            &BlockSize::Max1MB => 1 * 1024 * 1024,
            &BlockSize::Max4MB => 4 * 1024 * 1024,
        }
    }
}

#[derive(Clone, Debug)]
#[repr(u32)]
pub enum BlockMode {
    Linked = 0,
    Independent,
}

#[derive(Clone, Debug)]
#[repr(u32)]
pub enum ContentChecksum {
    NoChecksum = 0,
    ChecksumEnabled,
}

#[derive(Clone, Debug)]
#[repr(u32)]
pub enum FrameType {
    Frame = 0,
    SkippableFrame,
}

#[derive(Clone, Debug)]
#[repr(u32)]
pub enum BlockChecksum {
    NoBlockChecksum = 0,
    BlockChecksumEnabled,
}

#[derive(Debug)]
#[repr(C)]
pub struct LZ4FFrameInfo {
    pub block_size_id: BlockSize,
    pub block_mode: BlockMode,
    pub content_checksum_flag: ContentChecksum,
    pub frame_type: FrameType,
    pub content_size: c_ulonglong,
    pub dict_id: c_uint,
    pub block_checksum_flag: BlockChecksum,
}

#[derive(Debug)]
#[repr(C)]
pub struct LZ4FPreferences {
    pub frame_info: LZ4FFrameInfo,
    pub compression_level: c_uint,
    pub auto_flush: c_uint,
    pub favor_dec_speed: c_uint,
    pub reserved: [c_uint; 3],
}

#[derive(Debug)]
#[repr(C)]
pub struct LZ4FCompressOptions {
    pub stable_src: c_uint,


    pub reserved: [c_uint; 3],
}

#[derive(Debug)]
#[repr(C)]
pub struct LZ4FDecompressOptions {
    pub stable_dst: c_uint,

    pub reserved: [c_uint; 3],
}

#[derive(Debug)]
#[repr(C)]
pub struct LZ4StreamEncode(c_void);

#[derive(Debug)]
#[repr(C)]
pub struct LZ4StreamDecode(c_void);

pub const LZ4F_VERSION: c_uint = 100;

extern "C" {


    #[allow(non_snake_case)]
    pub fn LZ4_compress_default(
        source: *const c_char,
        dest: *mut c_char,
        sourceSize: c_int,
        maxDestSize: c_int,
    ) -> c_int;


    #[allow(non_snake_case)]
    pub fn LZ4_compress_fast(
        source: *const c_char,
        dest: *mut c_char,
        sourceSize: c_int,
        maxDestSize: c_int,
        acceleration: c_int,
    ) -> c_int;


    #[allow(non_snake_case)]
    pub fn LZ4_compress_HC(
        src: *const c_char,
        dst: *mut c_char,
        srcSize: c_int,
        dstCapacity: c_int,
        compressionLevel: c_int,
    ) -> c_int;


    #[allow(non_snake_case)]
    pub fn LZ4_decompress_safe(
        source: *const c_char,
        dest: *mut c_char,
        compressedSize: c_int,
        maxDecompressedSize: c_int,
    ) -> c_int;


    pub fn LZ4F_isError(code: size_t) -> c_uint;


    pub fn LZ4F_getErrorName(code: size_t) -> *const c_char;















    pub fn LZ4F_createCompressionContext(
        ctx: &mut LZ4FCompressionContext,
        version: c_uint,
    ) -> LZ4FErrorCode;



    pub fn LZ4F_freeCompressionContext(ctx: LZ4FCompressionContext) -> LZ4FErrorCode;














    pub fn LZ4F_compressBegin(
        ctx: LZ4FCompressionContext,
        dstBuffer: *mut u8,
        dstMaxSize: size_t,
        preferencesPtr: *const LZ4FPreferences,
    ) -> LZ4FErrorCode;








    pub fn LZ4F_compressBound(
        srcSize: size_t,
        preferencesPtr: *const LZ4FPreferences,
    ) -> LZ4FErrorCode;


















    pub fn LZ4F_compressUpdate(
        ctx: LZ4FCompressionContext,
        dstBuffer: *mut u8,
        dstMaxSize: size_t,
        srcBuffer: *const u8,
        srcSize: size_t,
        compressOptionsPtr: *const LZ4FCompressOptions,
    ) -> size_t;














    pub fn LZ4F_flush(
        ctx: LZ4FCompressionContext,
        dstBuffer: *mut u8,
        dstMaxSize: size_t,
        compressOptionsPtr: *const LZ4FCompressOptions,
    ) -> LZ4FErrorCode;















    pub fn LZ4F_compressEnd(
        ctx: LZ4FCompressionContext,
        dstBuffer: *mut u8,
        dstMaxSize: size_t,
        compressOptionsPtr: *const LZ4FCompressOptions,
    ) -> LZ4FErrorCode;















    pub fn LZ4F_createDecompressionContext(
        ctx: &mut LZ4FDecompressionContext,
        version: c_uint,
    ) -> LZ4FErrorCode;


    pub fn LZ4F_freeDecompressionContext(ctx: LZ4FDecompressionContext) -> LZ4FErrorCode;

















    pub fn LZ4F_getFrameInfo(
        ctx: LZ4FDecompressionContext,
        frameInfoPtr: &mut LZ4FFrameInfo,
        srcBuffer: *const u8,
        srcSizePtr: &mut size_t,
    ) -> LZ4FErrorCode;





































    pub fn LZ4F_decompress(
        ctx: LZ4FDecompressionContext,
        dstBuffer: *mut u8,
        dstSizePtr: &mut size_t,
        srcBuffer: *const u8,
        srcSizePtr: &mut size_t,
        optionsPtr: *const LZ4FDecompressOptions,
    ) -> LZ4FErrorCode;


    pub fn LZ4_versionNumber() -> c_int;


    pub fn LZ4_compressBound(size: c_int) -> c_int;


    pub fn LZ4_createStream() -> *mut LZ4StreamEncode;





    pub fn LZ4_compress_continue(
        LZ4_stream: *mut LZ4StreamEncode,
        source: *const u8,
        dest: *mut u8,
        input_size: c_int,
    ) -> c_int;


    pub fn LZ4_freeStream(LZ4_stream: *mut LZ4StreamEncode) -> c_int;




    pub fn LZ4_setStreamDecode(
        LZ4_stream: *mut LZ4StreamDecode,
        dictionary: *const u8,
        dict_size: c_int,
    ) -> c_int;


    pub fn LZ4_createStreamDecode() -> *mut LZ4StreamDecode;






    pub fn LZ4_decompress_safe_continue(
        LZ4_stream: *mut LZ4StreamDecode,
        source: *const u8,
        dest: *mut u8,
        compressed_size: c_int,
        max_decompressed_size: c_int,
    ) -> c_int;


    pub fn LZ4_freeStreamDecode(LZ4_stream: *mut LZ4StreamDecode) -> c_int;






    pub fn LZ4F_resetDecompressionContext(ctx: LZ4FDecompressionContext);

}

#[test]
fn test_version_number() {
    unsafe {
        LZ4_versionNumber();
    }
}

#[test]
fn test_frame_info_size() {
    assert_eq!(core::mem::size_of::<LZ4FFrameInfo>(), 32);
}
