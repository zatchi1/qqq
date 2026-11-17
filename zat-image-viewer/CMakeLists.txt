cmake_minimum_required(VERSION 3.16)
project(zat LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

add_executable(zat WIN32 src/zat.cpp res/zat.rc)

# Optional SVG support: place nanosvg.h / nanosvgrast.h in src/third_party/
if(EXISTS "${CMAKE_SOURCE_DIR}/src/third_party/nanosvg.h" AND EXISTS "${CMAKE_SOURCE_DIR}/src/third_party/nanosvgrast.h")
  target_compile_definitions(zat PRIVATE ZAT_NANOSVG)
  message(STATUS "zat: SVG support enabled (nanosvg)")
else()
  message(WARNING "zat: nanosvg not found, SVG support disabled")
endif()

target_compile_definitions(zat PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN_OFF)
target_link_libraries(zat PRIVATE gdiplus windowscodecs ole32 oleaut32 uuid comdlg32 shlwapi shell32 gdi32 user32 advapi32)

if(MINGW)
  target_compile_options(zat PRIVATE -Os -ffunction-sections -fdata-sections)
  target_link_options(zat PRIVATE -static -static-libgcc -static-libstdc++ -Wl,--gc-sections -s -mwindows)
endif()
