cmake_minimum_required(VERSION 3.1)
project(AdguardDns C CXX)

set(CMAKE_POLICY_DEFAULT_CMP0063 NEW) # allows visibility=hidden for static targets for subprojects
cmake_policy(SET CMP0063 NEW) # allows visibility=hidden for static targets for this project

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

if (MSVC)
    set(CMAKE_SHARED_LINKER_FLAGS_DEBUG "${CMAKE_SHARED_LINKER_FLAGS_DEBUG} /DEBUG /OPT:REF")
    set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} /DEBUG /OPT:REF")
    set(CMAKE_EXE_LINKER_FLAGS_DEBUG "${CMAKE_EXE_LINKER_FLAGS_DEBUG} /DEBUG /OPT:REF")
    set(CMAKE_EXE_LINKER_FLAGS_RELEASE "${CMAKE_EXE_LINKER_FLAGS_RELEASE} /DEBUG /OPT:REF")

    set(CompilerFlags
            CMAKE_CXX_FLAGS
            CMAKE_CXX_FLAGS_DEBUG
            CMAKE_CXX_FLAGS_RELEASE
            CMAKE_C_FLAGS
            CMAKE_C_FLAGS_DEBUG
            CMAKE_C_FLAGS_RELEASE
            )
    foreach (CompilerFlag ${CompilerFlags})
        string(REPLACE "/MDd" "/MT" ${CompilerFlag} "${${CompilerFlag}}")
        string(REPLACE "-MDd" "-MT" ${CompilerFlag} "${${CompilerFlag}}")
        string(REPLACE "/MTd" "/MT" ${CompilerFlag} "${${CompilerFlag}}")
        string(REPLACE "-MTd" "-MT" ${CompilerFlag} "${${CompilerFlag}}")
        string(REPLACE "/MD" "/MT" ${CompilerFlag} "${${CompilerFlag}}")
        string(REPLACE "-MD" "-MT" ${CompilerFlag} "${${CompilerFlag}}")
        string(REPLACE "/RTC1" "" ${CompilerFlag} "${${CompilerFlag}}")
    endforeach ()

    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} /MT /Zi /EHs-c- /W0")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /MT /Zi /EHs-c- /std:c++latest /W0")
else ()
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-strict-aliasing -fno-exceptions")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-strict-aliasing -fno-exceptions")
endif ()

add_definitions(-DFMT_EXCEPTIONS=0)

if (WIN32)
    # Minimum supported Windows API is 6.1 (Windows 7)
    add_definitions(-D_WIN32_WINNT=0x0601)
    add_definitions(-D__USE_MINGW_ANSI_STDIO=1 -D_CRT_SECURE_NO_WARNINGS -D_CRT_NONSTDC_NO_DEPRECATE)

    set(CMAKE_SHARED_LIBRARY_PREFIX "")
    set(CMAKE_SHARED_LIBRARY_SUFFIX ".dll")

    if (NOT MSVC) # WIN32 and not msvc => must be MinGW, link statically
        link_libraries(-static)
    endif ()
endif ()

set(DNS_LIBS_DIR ../../..)
set(THIRD_PARTY_DIR ../../../third-party)

if (NOT TARGET dnsproxy)
    add_subdirectory(${DNS_LIBS_DIR}/proxy ${CMAKE_BINARY_DIR}/dnsproxy)
endif ()

set(SOURCE_FILES
        src/ag_dns.cpp)

set(RES_FILES
        src/ag_dns.rc)

add_library(AdguardDns SHARED ${SOURCE_FILES} ${RES_FILES})
target_include_directories(AdguardDns PUBLIC include)
set_target_properties(AdguardDns PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_link_libraries(AdguardDns dnsproxy)

if (WIN32)
    if (NOT TARGET detours)
        add_subdirectory(${THIRD_PARTY_DIR}/detours ${CMAKE_BINARY_DIR}/detours)
    endif()
    target_link_libraries(AdguardDns detours)
endif()

# (Re)generate ag_dns_h_hash.inc
file(STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/include/ag_dns.h AG_DNSLIBS_H)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/include/ag_dnslibs.h.strings "${AG_DNSLIBS_H}")
file(SHA256 ${CMAKE_CURRENT_BINARY_DIR}/include/ag_dnslibs.h.strings AG_DNSLIBS_H_HASH)
file(REMOVE ${CMAKE_CURRENT_BINARY_DIR}/include/ag_dnslibs.h.strings)
file(WRITE ${CMAKE_CURRENT_SOURCE_DIR}/src/ag_dns_h_hash.inc "#define AG_DNSLIBS_H_HASH \"${AG_DNSLIBS_H_HASH}\"\n")

set(OUTPUT_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cs/Adguard.Dns/Adguard.Dns)
if (TARGET AdguardDns)
    if (MSVC)
        add_custom_command(TARGET AdguardDns
                POST_BUILD
                COMMAND "${CMAKE_COMMAND}" -E copy $<TARGET_FILE:AdguardDns> ${OUTPUT_LIB_DIR}
                COMMAND "${CMAKE_COMMAND}" -E copy $<TARGET_PDB_FILE:AdguardDns> ${OUTPUT_LIB_DIR}
                COMMENT "Copying to output directory")
    else ()
        add_custom_command(TARGET AdguardDns
                POST_BUILD
                COMMAND "${CMAKE_COMMAND}" -E copy $<TARGET_FILE:AdguardDns> ${OUTPUT_LIB_DIR}
                COMMENT "Copying to output directory")
    endif ()
endif ()

enable_testing()

if (NOT TARGET tests)
    add_custom_target(tests)
endif ()

link_libraries(AdguardDns)

add_executable(capi_test EXCLUDE_FROM_ALL test/capi_test.c)
add_test(capi_test capi_test)
add_dependencies(tests capi_test)
