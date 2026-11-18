cmake_minimum_required(VERSION 3.16)
project(zatchi LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/res/games.tsv")
  message(FATAL_ERROR "res/games.tsv is missing. Run: python tools/gen_games.py")
endif()

add_executable(zatchi WIN32 src/zatchi.cpp res/zatchi.rc)
set_source_files_properties(res/zatchi.rc PROPERTIES OBJECT_DEPENDS "${CMAKE_SOURCE_DIR}/res/games.tsv")

target_compile_definitions(zatchi PRIVATE UNICODE _UNICODE)
target_link_libraries(zatchi PRIVATE gdiplus ole32 uuid comdlg32 comctl32 shlwapi shell32 gdi32 user32 advapi32)

if(MINGW)
  target_compile_options(zatchi PRIVATE -Os -ffunction-sections -fdata-sections)
  target_link_options(zatchi PRIVATE -static -static-libgcc -static-libstdc++ -Wl,--gc-sections -s -mwindows)
endif()
