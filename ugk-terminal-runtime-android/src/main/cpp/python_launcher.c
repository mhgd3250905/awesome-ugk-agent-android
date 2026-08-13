#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

typedef int (*PyBytesMain)(int argc, char **argv);

/*
 * CPython's official Android distribution intentionally provides libpython,
 * not a console executable. This small PIE stays in APK nativeLibraryDir and
 * turns the embedded interpreter into the Runtime's `python` command without
 * extracting native code to app-writable storage.
 */
int main(int argc, char **argv) {
    const char *library_path = getenv("UGK_PYTHON_LIBRARY");
    if (library_path == NULL || library_path[0] == '\0') {
        fputs("UGK Python launcher: UGK_PYTHON_LIBRARY is not configured\n", stderr);
        return 127;
    }

    void *handle = dlopen(library_path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        fprintf(
            stderr,
            "UGK Python launcher: unable to load %s: %s\n",
            library_path,
            dlerror()
        );
        return 127;
    }

    dlerror();
    PyBytesMain py_bytes_main = (PyBytesMain)dlsym(handle, "Py_BytesMain");
    const char *symbol_error = dlerror();
    if (symbol_error != NULL || py_bytes_main == NULL) {
        fprintf(
            stderr,
            "UGK Python launcher: Py_BytesMain is unavailable: %s\n",
            symbol_error == NULL ? "unknown error" : symbol_error
        );
        return 127;
    }

    return py_bytes_main(argc, argv);
}
