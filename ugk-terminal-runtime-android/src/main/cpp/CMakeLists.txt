cmake_minimum_required(VERSION 3.22.1)

project(ugk_terminal_runtime LANGUAGES C)

function(configure_pie_executable target)
    target_compile_features(${target} PRIVATE c_std_11)
    target_compile_options(${target} PRIVATE
        -fPIE
        -fstack-protector-strong
        -D_FORTIFY_SOURCE=2
    )
    target_link_options(${target} PRIVATE
        -pie
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-Wl,-z,relro"
        "-Wl,-z,now"
    )
endfunction()

function(configure_android_payload_name target output_name)
    # Android only packages native payloads with lib*.so names. This remains
    # an ELF executable (not a JNI shared library) so it can be ProcessBuilder
    # launched from nativeLibraryDir.
    set_target_properties(${target} PROPERTIES
        OUTPUT_NAME "${output_name}"
        PREFIX "lib"
        SUFFIX ".so"
    )
endfunction()

add_executable(ugk_runtime_probe probe.c)
configure_android_payload_name(ugk_runtime_probe "ugk_runtime_probe")
configure_pie_executable(ugk_runtime_probe)

add_executable(ugk_session_launcher session_launcher.c)
configure_android_payload_name(ugk_session_launcher "ugk_session_launcher")
configure_pie_executable(ugk_session_launcher)

add_executable(ugk_python python_launcher.c)
configure_android_payload_name(ugk_python "ugk_python")
configure_pie_executable(ugk_python)
target_link_libraries(ugk_python PRIVATE dl)

add_library(ugk_terminal_native SHARED process_group_control.c)
target_compile_features(ugk_terminal_native PRIVATE c_std_11)
target_compile_options(ugk_terminal_native PRIVATE
    -fPIC
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
)
target_link_options(ugk_terminal_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    "-Wl,-z,relro"
    "-Wl,-z,now"
)
