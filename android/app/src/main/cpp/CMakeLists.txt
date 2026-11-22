cmake_minimum_required(VERSION 3.22.1)
project(fpdriver LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)

# libadrenotools (BSD-2-Clause, Billy Laws): custom Vulkan driver loading, plus its hook
# libraries, which must be packaged as their own .so files next to ours.
add_subdirectory(libadrenotools)

add_library(fpdriver SHARED fpdriver.cpp)
target_link_libraries(fpdriver adrenotools log dl)
target_link_options(fpdriver PRIVATE "-Wl,-z,max-page-size=16384")

# Discord Rich Presence (ARMSX2's discord_bridge.cpp). The Discord Social SDK is a private, optional
# build input (DISCORD_SDK_DIR: include/discordpp.h, <abi>/libdiscord_partner_sdk.so); Discord's terms
# allow shipping it inside the app but not republishing it, so it is never in this repository.
# Without it, or without ARMFlash's application id, the feature compiles out.
set(DISCORD_SDK_SO "${DISCORD_SDK_DIR}/${ANDROID_ABI}/libdiscord_partner_sdk.so")
if(DISCORD_SDK_DIR AND FLASHARM_DISCORD_APP_ID AND EXISTS "${DISCORD_SDK_SO}" AND EXISTS "${DISCORD_SDK_DIR}/include/discordpp.h")
    add_library(discord_partner_sdk SHARED IMPORTED)
    set_target_properties(discord_partner_sdk PROPERTIES
        IMPORTED_LOCATION "${DISCORD_SDK_SO}"
        INTERFACE_INCLUDE_DIRECTORIES "${DISCORD_SDK_DIR}/include")
    add_library(flasharm_discord SHARED discord_bridge.cpp)
    target_compile_definitions(flasharm_discord PRIVATE
        FLASHARM_DISCORD_APP_ID=${FLASHARM_DISCORD_APP_ID}ull
        FLASHARM_DISCORD_APP_ID_STR="${FLASHARM_DISCORD_APP_ID}")
    target_link_libraries(flasharm_discord discord_partner_sdk log android)
    target_link_options(flasharm_discord PRIVATE "-Wl,-z,max-page-size=16384")
    message(STATUS "Discord Rich Presence: on (application ${FLASHARM_DISCORD_APP_ID})")
else()
    message(STATUS "Discord Rich Presence: compiled out (needs DISCORD_SDK_DIR and FLASHARM_DISCORD_APP_ID)")
endif()
