"""UGK Terminal Runtime native-extension import bridge.

Android extracts only native library entries whose filename begins with ``lib``
into an application's ``nativeLibraryDir``. CPython extension modules use names
such as ``_sqlite3.cpython-314-...so`` instead, so the Runtime packages them as
``libugk_pyext_<original-name>`` and resolves them here before normal imports.

This file runs only from the Runtime-managed standard library. The directory is
supplied by a Runtime-managed environment variable; user-supplied environment
values are rejected by BashRuntime.
"""

import importlib.machinery
import importlib.util
import os
import sys


_EXTENSION_PREFIX = "libugk_pyext_"
_extension_directory = os.environ.get("UGK_PYTHON_EXTENSION_DIRECTORY")


class _UgkNativeExtensionFinder:
    def find_spec(self, fullname, path=None, target=None):
        # The official CPython Android payload currently exposes only top-level
        # production extensions. Avoid intercepting package submodule lookups.
        if not _extension_directory or "." in fullname:
            return None

        for suffix in importlib.machinery.EXTENSION_SUFFIXES:
            candidate = os.path.join(
                _extension_directory,
                _EXTENSION_PREFIX + fullname + suffix,
            )
            if os.path.isfile(candidate):
                loader = importlib.machinery.ExtensionFileLoader(fullname, candidate)
                return importlib.util.spec_from_file_location(
                    fullname,
                    candidate,
                    loader=loader,
                )
        return None


if _extension_directory:
    sys.meta_path.insert(0, _UgkNativeExtensionFinder())
