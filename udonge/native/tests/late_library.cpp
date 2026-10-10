#include <unistd.h>
#include <dlfcn.h>

extern "C" int library_access(const char *path) {
    return access(path, F_OK);
}

extern "C" int library_symbol() { return 1; }

extern "C" int library_next_symbol() {
    auto next = reinterpret_cast<int (*)()>(dlsym(RTLD_NEXT, "library_symbol"));
    return next ? next() : -1;
}
