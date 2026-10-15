cmake_minimum_required(VERSION 3.22.1)
project(jarvis_native)

add_library(jarvis_native SHARED native_bridge.cpp)
