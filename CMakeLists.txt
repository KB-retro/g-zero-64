# G-Diffuser — F-Zero X PC source port
# Top-level build (scaffold — fleshed out in milestone M0).
cmake_minimum_required(VERSION 3.20)

project(GDiffuser
    LANGUAGES C CXX
    DESCRIPTION "F-Zero X PC source port"
)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# --- Submodule presence check -------------------------------------------------
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/libultraship/CMakeLists.txt")
    message(FATAL_ERROR
        "Submodules not initialized. Run:\n"
        "  git submodule update --init --recursive")
endif()

# Match libultraship's static C runtime (/MT) across the whole tree, or the final link
# fails with a RuntimeLibrary mismatch (LUS = MT_StaticRelease, default = MD_DynamicRelease).
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# Fused multiply-add contraction changes rounding in physics code and silently diverges
# from the decomp baseline; MSVC already defaults to no-contraction unless /fp:fast is set.
add_compile_options(
    $<$<OR:$<C_COMPILER_ID:GNU>,$<CXX_COMPILER_ID:GNU>>:-ffp-contract=off>
    $<$<OR:$<C_COMPILER_ID:Clang>,$<CXX_COMPILER_ID:Clang>,$<C_COMPILER_ID:AppleClang>,$<CXX_COMPILER_ID:AppleClang>>:-ffp-contract=off>
)

# --- Components ---------------------------------------------------------------
if(ANDROID)
    # GLES 3 renderer; ImGui's GL backend needs the matching define (libultraship sets it on Linux).
    set(USE_OPENGLES ON CACHE BOOL "" FORCE)
    add_compile_definitions(IMGUI_IMPL_OPENGL_ES3)
    # GLES 3.0 has no border clamp (3.2 only); the post-process passes fall back to edge clamp.
    add_compile_definitions(GL_CLAMP_TO_BORDER=GL_CLAMP_TO_EDGE)
endif()
add_subdirectory(libultraship)     # runtime + Fast3D renderer
add_subdirectory(port)             # game glue + decomp sources + G-Diffuser executable
#
# Asset extraction: port/CMakeLists.txt builds the standalone `gdx-extract` (a trimmed,
# deterministic Torch, BUILD_STORMLIB=OFF) as an isolated ExternalProject and deploys it
# plus the decomp-recipes/ tree beside the executable. First-boot spawns it against the
# user-supplied ROM to produce generic.o2r. Build directly with:
#   cmake --build <build> --target gdx-extract

message(STATUS "G-Diffuser: libultraship wired via add_subdirectory.")
