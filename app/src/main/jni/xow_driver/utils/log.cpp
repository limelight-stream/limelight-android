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

#include "log.h"
#include "bytes.h"

#include <sstream>
#include <iomanip>

namespace Log
{
    std::string formatBytes(const Bytes &bytes)
    {
        std::ostringstream stream;

        stream << std::hex << std::setfill('0');

        for (uint8_t byte : bytes)
        {
            stream << std::setw(2);
            stream << static_cast<uint32_t>(byte) << ':';
        }

        std::string output = stream.str();

        // Remove trailing colon
        output.pop_back();

        return output;
    }

    std::string formatLog(std::string level, std::string message)
    {
        std::ostringstream stream;
        std::time_t time = std::time(nullptr);
        std::tm localTime = {};

        // Add local time to output if available
        if (localtime_r(&time, &localTime))
        {
            stream << std::put_time(&localTime, "%F %T") << " ";
        }

        stream << std::left << std::setw(5);
        stream << level << " - ";
        stream << message << std::endl;

        return stream.str();
    }
}
