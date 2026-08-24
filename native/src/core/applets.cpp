#include <libgen.h>
#include <sys/stat.h>

#include <consts.hpp>
#include <core.hpp>

using namespace std;

struct Applet {
    string_view name;
    int (*fn)(int, char *[]);
};

constexpr Applet applets[] = {
    { "su", su_client_main },
    { "resetprop", resetprop_main },
};

constexpr Applet private_applets[] = {
    { "zygisk", zygisk_main },
};

int main(int argc, char *argv[]) {
    if (argc < 1)
        return 1;

    cmdline_logging();
    init_argv0(argc, argv);

    Utf8CStr argv0 = basename(argv[0]);

    umask(0);

    if (argv[0][0] == '\0') {

        if (argc < 2)
            return 1;
        --argc;
        ++argv;
        for (const auto &app : private_applets) {
            if (argv[0] == app.name) {
                return app.fn(argc, argv);
            }
        }
        fprintf(stderr, "%s: applet not found\n", argv[0]);
        return 1;
    }

    if (argv0 == MAIN_BIN_NAME || argv0 == MAIN_BIN_NAME "32" || argv0 == MAIN_BIN_NAME "64" ||
        argv0 == RAMDISK_BIN_NAME) {
        if (argc > 1 && argv[1][0] != '-') {

            --argc;
            ++argv;
            argv0 = argv[0];
        } else {
            return magisk_main(argc, argv);
        }
    }

    for (const auto &app : applets) {
        if (argv0 == app.name) {
            return app.fn(argc, argv);
        }
    }
    fprintf(stderr, "%s: applet not found\n", argv0.c_str());
    return 1;
}
