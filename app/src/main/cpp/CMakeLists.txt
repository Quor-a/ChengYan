cmake_minimum_required(VERSION 3.18.1)
project("chengyan_pty")
add_library(pty SHARED pty.c)
target_link_libraries(pty log)
