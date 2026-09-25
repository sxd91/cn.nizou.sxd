cmake_minimum_required(VERSION 3.22)
project(signprobe_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# 静态链接 libc++_shared 以免和宿主冲突
add_library(${CMAKE_PROJECT_NAME} SHARED signprobe_native.cpp)

# prefab 引入 shadowhook
find_package(shadowhook REQUIRED CONFIG)

target_link_libraries(${CMAKE_PROJECT_NAME}
    shadowhook::shadowhook
    log)
