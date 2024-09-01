/*
 * Copyright (C) 2019 Medusalix
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

#pragma once

#include <string>
#include <android/log.h>

class Bytes;
#define APPNAME "xow-driver"

#define PRImac "%02x:%02x:%02x:%02x:%02x:%02x"
#define VALmac(arr) arr[0],arr[1],arr[2],arr[3],arr[4],arr[5]

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wformat-security"

/*
 * Provides logging functions for different log levels
 * Debug logging can be enabled by defining DEBUG
 */
namespace Log
{
    std::string formatBytes(const Bytes &bytes);
    std::string formatLog(std::string level, std::string message);

    inline void init()
    {
        // Switch to line buffering
        setlinebuf(stdout);
    }

    inline void debug(const char *message)
    {
        __android_log_print(ANDROID_LOG_DEBUG, APPNAME, message);
    }

    template<typename... Args>
    inline void debug(const char * message, Args... args)
    {
        __android_log_print(ANDROID_LOG_DEBUG, APPNAME, message, args...);
    }

    inline void info(const char * message)
    {
        __android_log_print(ANDROID_LOG_INFO, APPNAME, message);
    }

    template<typename... Args>
    inline void info(const char * message, Args... args)
    {
        __android_log_print(ANDROID_LOG_INFO, APPNAME, message, args...);
    }

    inline void error(const char * message)
    {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, message);
    }

    template<typename... Args>
    inline void error(const char * message, Args... args)
    {
        __android_log_print(ANDROID_LOG_ERROR, APPNAME, message, args...);
    }
}

#pragma GCC diagnostic pop

