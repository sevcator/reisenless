#pragma once

#include <cstdint>
#include <utility>
#include <bitset>
#include <rust/cxx.h>

enum class FileFormat : uint8_t;

/******************
 * Special Headers
 *****************/

struct mtk_hdr {
    uint32_t magic;
    uint32_t size;
    char name[32];

    char padding[472];
} __attribute__((packed));

struct dhtb_hdr {
    char magic[8];
    uint8_t checksum[40];
    uint32_t size;

    char padding[460];
} __attribute__((packed));

struct blob_hdr {
    char secure_magic[20];
    uint32_t datalen;
    uint32_t signature;
    char magic[16];
    uint32_t hdr_version;
    uint32_t hdr_size;
    uint32_t part_offset;
    uint32_t num_parts;
    uint32_t unknown[7];
    char name[4];
    uint32_t offset;
    uint32_t size;
    uint32_t version;
} __attribute__((packed));

/**************
 * AVB Headers
 **************/

#define AVB_FOOTER_MAGIC_LEN 4
#define AVB_MAGIC_LEN 4
#define AVB_RELEASE_STRING_SIZE 48


struct AvbFooter {
    uint8_t magic[AVB_FOOTER_MAGIC_LEN];
    uint32_t version_major;
    uint32_t version_minor;
    uint64_t original_image_size;
    uint64_t vbmeta_offset;
    uint64_t vbmeta_size;
    uint8_t reserved[28];
} __attribute__((packed));


struct AvbVBMetaImageHeader {
    uint8_t magic[AVB_MAGIC_LEN];
    uint32_t required_libavb_version_major;
    uint32_t required_libavb_version_minor;
    uint64_t authentication_data_block_size;
    uint64_t auxiliary_data_block_size;
    uint32_t algorithm_type;
    uint64_t hash_offset;
    uint64_t hash_size;
    uint64_t signature_offset;
    uint64_t signature_size;
    uint64_t public_key_offset;
    uint64_t public_key_size;
    uint64_t public_key_metadata_offset;
    uint64_t public_key_metadata_size;
    uint64_t descriptors_offset;
    uint64_t descriptors_size;
    uint64_t rollback_index;
    uint32_t flags;
    uint32_t rollback_index_location;
    uint8_t release_string[AVB_RELEASE_STRING_SIZE];
    uint8_t reserved[80];
} __attribute__((packed));







#define BOOT_MAGIC_SIZE 8
#define BOOT_NAME_SIZE 16
#define BOOT_ID_SIZE 32
#define BOOT_ARGS_SIZE 512
#define BOOT_EXTRA_ARGS_SIZE 1024
#define VENDOR_BOOT_ARGS_SIZE 2048
#define VENDOR_RAMDISK_NAME_SIZE 32
#define VENDOR_RAMDISK_TABLE_ENTRY_BOARD_ID_SIZE 16

#define VENDOR_RAMDISK_TYPE_NONE 0
#define VENDOR_RAMDISK_TYPE_PLATFORM 1
#define VENDOR_RAMDISK_TYPE_RECOVERY 2
#define VENDOR_RAMDISK_TYPE_DLKM 3





























struct boot_img_hdr_v0_common {
    char magic[BOOT_MAGIC_SIZE];

    uint32_t kernel_size;
    uint32_t kernel_addr;

    uint32_t ramdisk_size;
    uint32_t ramdisk_addr;

    uint32_t second_size;
    uint32_t second_addr;
} __attribute__((packed));

struct boot_img_hdr_v0 : public boot_img_hdr_v0_common {
    uint32_t tags_addr;





    union {
        uint32_t unknown;
        uint32_t page_size;
    };




    union {
        uint32_t header_version;
        uint32_t extra_size;
    };





    uint32_t os_version;

    char name[BOOT_NAME_SIZE];
    char cmdline[BOOT_ARGS_SIZE];
    char id[BOOT_ID_SIZE];



    char extra_cmdline[BOOT_EXTRA_ARGS_SIZE];
} __attribute__((packed));

struct boot_img_hdr_v1 : public boot_img_hdr_v0 {
    uint32_t recovery_dtbo_size;
    uint64_t recovery_dtbo_offset;
    uint32_t header_size;
} __attribute__((packed));

struct boot_img_hdr_v2 : public boot_img_hdr_v1 {
    uint32_t dtb_size;
    uint64_t dtb_addr;
} __attribute__((packed));


struct boot_img_hdr_pxa : public boot_img_hdr_v0_common {
    uint32_t extra_size;
    uint32_t unknown;
    uint32_t tags_addr;
    uint32_t page_size;

    char name[24];
    char cmdline[BOOT_ARGS_SIZE];
    char id[BOOT_ID_SIZE];

    char extra_cmdline[BOOT_EXTRA_ARGS_SIZE];
} __attribute__((packed));










































































struct boot_img_hdr_v3 {
    uint8_t magic[BOOT_MAGIC_SIZE];

    uint32_t kernel_size;
    uint32_t ramdisk_size;
    uint32_t os_version;
    uint32_t header_size;
    uint32_t reserved[4];

    uint32_t header_version;

    char cmdline[BOOT_ARGS_SIZE + BOOT_EXTRA_ARGS_SIZE];
} __attribute__((packed));

struct boot_img_hdr_vnd_v3 {

    uint8_t magic[BOOT_MAGIC_SIZE];

    uint32_t header_version;
    uint32_t page_size;
    uint32_t kernel_addr;
    uint32_t ramdisk_addr;
    uint32_t ramdisk_size;
    char cmdline[VENDOR_BOOT_ARGS_SIZE];
    uint32_t tags_addr;
    char name[BOOT_NAME_SIZE];
    uint32_t header_size;
    uint32_t dtb_size;
    uint64_t dtb_addr;
} __attribute__((packed));

struct boot_img_hdr_v4 : public boot_img_hdr_v3 {
    uint32_t signature_size;
} __attribute__((packed));

struct boot_img_hdr_vnd_v4 : public boot_img_hdr_vnd_v3 {
    uint32_t vendor_ramdisk_table_size;
    uint32_t vendor_ramdisk_table_entry_num;
    uint32_t vendor_ramdisk_table_entry_size;
    uint32_t bootconfig_size;
} __attribute__((packed));

struct vendor_ramdisk_table_entry_v4 {
    uint32_t ramdisk_size;
    uint32_t ramdisk_offset;
    uint32_t ramdisk_type;
    char ramdisk_name[VENDOR_RAMDISK_NAME_SIZE];



    uint32_t board_id[VENDOR_RAMDISK_TABLE_ENTRY_BOARD_ID_SIZE];
} __attribute__((packed));





template <typename T>
static T align_to(T v, int a) {
    static_assert(std::is_integral_v<T>);
    return (v + a - 1) / a * a;
}

template <typename T>
static T align_padding(T v, int a) {
    return align_to(v, a) - v;
}

#define decl_val(name, len) \
virtual uint##len##_t name() const { return 0; }

#define decl_var(name, len) \
virtual uint##len##_t &name() { return j##len(); } \
decl_val(name, len)

#define decl_str(name) \
virtual char *name() { return nullptr; } \
virtual const char *name() const { return nullptr; }

struct dyn_img_hdr {

    virtual bool is_vendor() const = 0;


    decl_var(kernel_size, 32)
    decl_var(ramdisk_size, 32)
    decl_var(second_size, 32)
    decl_val(page_size, 32)
    decl_val(header_version, 32)
    decl_var(extra_size, 32)
    decl_var(os_version, 32)
    decl_str(name)
    decl_str(cmdline)
    decl_str(id)
    decl_str(extra_cmdline)


    decl_var(recovery_dtbo_size, 32)
    decl_var(recovery_dtbo_offset, 64)
    decl_var(header_size, 32)
    decl_var(dtb_size, 32)


    decl_val(signature_size, 32)


    decl_val(vendor_ramdisk_table_size, 32)
    decl_val(vendor_ramdisk_table_entry_num, 32)
    decl_val(vendor_ramdisk_table_entry_size, 32)
    decl_var(bootconfig_size, 32)

    virtual ~dyn_img_hdr() {
        free(raw);
    }

    virtual size_t hdr_size() const = 0;
    virtual size_t hdr_space() const { return page_size(); }
    virtual dyn_img_hdr *clone() const = 0;

    const void *raw_hdr() const { return raw; }
    void print() const;
    void dump_hdr_file() const;
    void load_hdr_file();

protected:
    union {
        boot_img_hdr_v2 *v2_hdr;
        boot_img_hdr_v4 *v4_hdr;
        boot_img_hdr_vnd_v4 *v4_vnd;
        boot_img_hdr_pxa *hdr_pxa;
        void *raw;
    };

    static uint32_t &j32() { _j32 = 0; return _j32; }
    static uint64_t &j64() { _j64 = 0; return _j64; }

private:

    inline static uint32_t _j32 = 0;
    inline static uint64_t _j64 = 0;
};

#undef decl_var
#undef decl_val
#undef decl_str

#define __impl_cls(name, hdr)           \
protected: name() = default;            \
public:                                 \
explicit                                \
name(const void *p, ssize_t sz = -1) {  \
    if (sz < 0) sz = sizeof(hdr);       \
    raw = calloc(sizeof(hdr), 1);       \
    memcpy(raw, p, sz);                 \
}                                       \
size_t hdr_size() const override {      \
    return sizeof(hdr);                 \
}                                       \
dyn_img_hdr *clone() const override {   \
    auto p = new name(raw);             \
    return p;                           \
};

#define __impl_val(name, hdr_name) \
decltype(std::declval<const dyn_img_hdr>().name()) name() const override { return hdr_name->name; }

#define __impl_var(name, hdr_name) \
decltype(std::declval<dyn_img_hdr>().name()) name() override { return hdr_name->name; } \
__impl_val(name, hdr_name)

#define impl_cls(ver)  __impl_cls(dyn_img_##ver, boot_img_hdr_##ver)
#define impl_val(name) __impl_val(name, v2_hdr)
#define impl_var(name) __impl_var(name, v2_hdr)

struct dyn_img_hdr_boot : public dyn_img_hdr {
    bool is_vendor() const final { return false; }
};

struct dyn_img_common : public dyn_img_hdr_boot {
    impl_var(kernel_size)
    impl_var(ramdisk_size)
    impl_var(second_size)
};

struct dyn_img_v0 : public dyn_img_common {
    impl_cls(v0)

    impl_val(page_size)
    impl_var(extra_size)
    impl_var(os_version)
    impl_var(name)
    impl_var(cmdline)
    impl_var(id)
    impl_var(extra_cmdline)
};

struct dyn_img_v1 : public dyn_img_v0 {
    impl_cls(v1)

    impl_val(header_version)
    impl_var(recovery_dtbo_size)
    impl_var(recovery_dtbo_offset)
    impl_var(header_size)

    uint32_t &extra_size() override { return j32(); }
    uint32_t extra_size() const override { return 0; }
};

struct dyn_img_v2 : public dyn_img_v1 {
    impl_cls(v2)

    impl_var(dtb_size)
};

#undef impl_val
#undef impl_var
#define impl_val(name) __impl_val(name, hdr_pxa)
#define impl_var(name) __impl_var(name, hdr_pxa)

struct dyn_img_pxa : public dyn_img_common {
    impl_cls(pxa)

    impl_var(extra_size)
    impl_val(page_size)
    impl_var(name)
    impl_var(cmdline)
    impl_var(id)
    impl_var(extra_cmdline)
};

#undef impl_val
#undef impl_var
#define impl_val(name) __impl_val(name, v4_hdr)
#define impl_var(name) __impl_var(name, v4_hdr)

struct dyn_img_v3 : public dyn_img_hdr_boot {
    impl_cls(v3)

    impl_var(kernel_size)
    impl_var(ramdisk_size)
    impl_var(os_version)
    impl_var(header_size)
    impl_val(header_version)
    impl_var(cmdline)


    uint32_t page_size() const override { return 4096; }
    char *extra_cmdline() override { return &v4_hdr->cmdline[BOOT_ARGS_SIZE]; }
    const char *extra_cmdline() const override { return &v4_hdr->cmdline[BOOT_ARGS_SIZE]; }
};

struct dyn_img_v4 : public dyn_img_v3 {
    impl_cls(v4)

    impl_val(signature_size)
};

struct dyn_img_hdr_vendor : public dyn_img_hdr {
    bool is_vendor() const final { return true; }
};

#undef impl_val
#undef impl_var
#define impl_val(name) __impl_val(name, v4_vnd)
#define impl_var(name) __impl_var(name, v4_vnd)

struct dyn_img_vnd_v3 : public dyn_img_hdr_vendor {
    impl_cls(vnd_v3)

    impl_val(header_version)
    impl_val(page_size)
    impl_var(ramdisk_size)
    impl_var(cmdline)
    impl_var(name)
    impl_var(header_size)
    impl_var(dtb_size)

    size_t hdr_space() const override { return align_to(hdr_size(), page_size()); }


    char *extra_cmdline() override { return &v4_vnd->cmdline[BOOT_ARGS_SIZE]; }
    const char *extra_cmdline() const override { return &v4_vnd->cmdline[BOOT_ARGS_SIZE]; }
};

struct dyn_img_vnd_v4 : public dyn_img_vnd_v3 {
    impl_cls(vnd_v4)

    impl_val(vendor_ramdisk_table_size)
    impl_val(vendor_ramdisk_table_entry_num)
    impl_val(vendor_ramdisk_table_entry_size)
    impl_var(bootconfig_size)
};

#undef __impl_cls
#undef __impl_val
#undef __impl_var
#undef impl_cls
#undef impl_val
#undef impl_var





enum {
    MTK_KERNEL,
    MTK_RAMDISK,
    CHROMEOS_FLAG,
    DHTB_FLAG,
    SEANDROID_FLAG,
    LG_BUMP_FLAG,
    SHA256_FLAG,
    BLOB_FLAG,
    NOOKHD_FLAG,
    ACCLAIM_FLAG,
    AMONET_FLAG,
    AVB1_SIGNED_FLAG,
    AVB_FLAG,
    ZIMAGE_KERNEL,
    BOOT_FLAGS_MAX
};

struct ZImage;

struct boot_img {

    const mmap_data map;


    dyn_img_hdr *hdr = nullptr;


    std::bitset<BOOT_FLAGS_MAX> flags;


    FileFormat k_fmt;
    FileFormat r_fmt;
    FileFormat e_fmt;














    byte_view payload;
    byte_view tail;


    const mtk_hdr *k_hdr = nullptr;
    const mtk_hdr *r_hdr = nullptr;

    std::unique_ptr<ZImage> z_info;


    const AvbFooter *avb_footer = nullptr;
    const AvbVBMetaImageHeader *vbmeta = nullptr;


    const uint8_t *kernel = nullptr;
    const uint8_t *ramdisk = nullptr;
    const uint8_t *second = nullptr;
    const uint8_t *extra = nullptr;
    const uint8_t *recovery_dtbo = nullptr;
    const uint8_t *dtb = nullptr;
    const uint8_t *signature = nullptr;
    const uint8_t *vendor_ramdisk_table = nullptr;
    const uint8_t *bootconfig = nullptr;


    byte_view kernel_dtb;

    explicit boot_img(const char *);
    ~boot_img();

    bool parse_image(const uint8_t *addr, FileFormat type);
    const uint8_t *parse_hdr(const uint8_t *addr, FileFormat type);
    std::span<const vendor_ramdisk_table_entry_v4> vendor_ramdisk_tbl() const;


    static std::unique_ptr<boot_img> create(Utf8CStr name) { return std::make_unique<boot_img>(name.c_str()); }
    rust::Slice<const uint8_t> get_payload() const { return payload; }
    rust::Slice<const uint8_t> get_tail() const { return tail; }
    bool is_signed() const { return flags[AVB1_SIGNED_FLAG]; }
    uint64_t tail_off() const { return tail.data() - map.data(); }


    bool verify() const noexcept;
};
