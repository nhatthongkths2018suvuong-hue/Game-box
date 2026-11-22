cmake_minimum_required(VERSION 3.18.1)
project(minibridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(minibridge SHARED bridge.cpp)
find_library(log-lib log)
target_link_libraries(minibridge android ${log-lib})
