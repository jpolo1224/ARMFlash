cmake_minimum_required(VERSION 3.22.1)
project(fpdriver LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)

# libadrenotools (BSD-2-Clause, Billy Laws): custom Vulkan driver loading, plus its hook
# libraries, which must be packaged as their own .so files next to ours.
add_subdirectory(libadrenotools)

add_library(fpdriver SHARED fpdriver.cpp)
target_link_libraries(fpdriver adrenotools log dl)
target_link_options(fpdriver PRIVATE "-Wl,-z,max-page-size=16384")
