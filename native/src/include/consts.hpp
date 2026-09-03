#pragma once

#include <flags.h>

#define JAVA_PACKAGE_NAME BUILD_APP_PACKAGE_NAME

#define SECURE_DIR      BUILD_SECURE_DIR
#define MODULEROOT      SECURE_DIR "/modules"
#define DATABIN         SECURE_DIR "/" BUILD_DATA_DIR
#define MAGISKDB        SECURE_DIR "/" BUILD_DB_NAME


#define INTLROOT      BUILD_INTERNAL_DIR
#define MIRRDIR       INTLROOT "/mirror"
#define PREINITMIRR   INTLROOT "/preinit"
#define DEVICEDIR     INTLROOT "/device"
#define PREINITDEV    DEVICEDIR "/preinit"
#define WORKERDIR     INTLROOT "/worker"
#define BBPATH        INTLROOT "/" BUILD_BUSYBOX_NAME
#define ROOTOVL       INTLROOT "/rootdir"
#define SHELLPTS      INTLROOT "/pts"
#define MAIN_CONFIG   INTLROOT "/config"
#define MAIN_SOCKET   DEVICEDIR "/" BUILD_SOCKET_NAME

constexpr const char *applet_names[] = { "su", "resetprop", nullptr };

#define POST_FS_DATA_WAIT_TIME       40
#define POST_FS_DATA_SCRIPT_MAX_TIME 35



#define MAIN_BIN_NAME    BUILD_ID
#define POLICY_BIN_NAME  BUILD_ID "p"
#define DAEMON_PROC_NAME BUILD_ID "d"

#define WORKER_SOURCE    BUILD_ID


#define ZYGISKLDR     "lib" BUILD_ID "z.so"
#define ZYGISKD64     BUILD_ID "d64"
#define ZYGISKD32     BUILD_ID "d32"


#define RAMDISK_BIN_NAME BUILD_RAMDISK_NAME

#define BACKUP_CONFIG    ".backup/" BUILD_BACKUP_CONFIG


#define SEPOL_PROC_DOMAIN   BUILD_PROC_DOMAIN
#define MAGISK_PROC_CON     "u:r:" SEPOL_PROC_DOMAIN ":s0"

#define SEPOL_FILE_TYPE     BUILD_FILE_TYPE
#define MAGISK_FILE_CON     "u:object_r:" SEPOL_FILE_TYPE ":s0"
