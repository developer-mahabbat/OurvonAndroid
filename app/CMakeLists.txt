cmake_minimum_required(VERSION 3.10)
project("nodejs_mobile_bridge")

add_library(rn_bridge SHARED
    src/main/cpp/rn-bridge.cpp
    src/main/cpp/native-lib.cpp
)

target_include_directories(rn_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libnode/include/node
)

target_link_directories(rn_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libnode/bin/${ANDROID_ABI}
)

target_link_libraries(rn_bridge
    node
    log
)

set_target_properties(rn_bridge PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)
