cmake_minimum_required(VERSION 3.22.1)
project(godhand_android LANGUAGES CXX)

add_library(godhand_android SHARED native-lib.cpp)

target_compile_features(godhand_android PRIVATE cxx_std_17)
target_compile_options(godhand_android PRIVATE -Wall -Wextra -Werror)
find_library(log-lib log)
target_link_libraries(godhand_android PRIVATE ${log-lib})
