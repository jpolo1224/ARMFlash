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
        FLASHARM_HAS_DISCORD=1
        FLASHARM_DISCORD_APP_ID=${FLASHARM_DISCORD_APP_ID}ull
        FLASHARM_DISCORD_APP_ID_STR="${FLASHARM_DISCORD_APP_ID}")
    target_link_libraries(flasharm_discord discord_partner_sdk log android)
    target_link_options(flasharm_discord PRIVATE "-Wl,-z,max-page-size=16384")
    message(STATUS "Discord Rich Presence: on (application ${FLASHARM_DISCORD_APP_ID})")
else()
    message(STATUS "Discord Rich Presence: compiled out (needs DISCORD_SDK_DIR and FLASHARM_DISCORD_APP_ID)")
endif()

# LSFG frame generation for native Flash (lsfg/armflash_lsfg.h): ARMSX2's FrameGen passes (from Eden
# and lsfg-vk, GPL-3.0-or-later) in a library of their own, loaded by fp-ruffle with dlopen. The
# GitHub build only (ARMFLASH_LSFG): the Play build must not carry frame generation at all.
if(ARMFLASH_LSFG)
    file(GLOB ARMFLASH_LSFG_FRAMEGEN CONFIGURE_DEPENDS lsfg/FrameGen/*.cpp)
    add_library(armflash_lsfg SHARED lsfg/armflash_lsfg.cpp lsfg/compat/armflash_compat.cpp ${ARMFLASH_LSFG_FRAMEGEN})
    set_target_properties(armflash_lsfg PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON
        CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
    target_include_directories(armflash_lsfg PRIVATE lsfg lsfg/compat lsfg/FrameGen)
    target_compile_options(armflash_lsfg PRIVATE -Wall -Wno-unused-function -Wno-missing-braces)
    # Only the armflash_lsfg_* entry points are exported; the vk* function pointers stay inside.
    target_link_options(armflash_lsfg PRIVATE "-Wl,-z,max-page-size=16384"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/lsfg/armflash_lsfg.map")
    set_target_properties(armflash_lsfg PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/lsfg/armflash_lsfg.map)
    target_link_libraries(armflash_lsfg android log)
    message(STATUS "LSFG frame generation: on")
else()
    message(STATUS "LSFG frame generation: not in this build")
endif()
