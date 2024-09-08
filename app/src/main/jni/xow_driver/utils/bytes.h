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

#include <cstddef>
#include <cstdint>
#include <vector>
#include <array>

/*
 * Simple wrapper for byte vectors
 * Provides utility functions
 */
class Bytes
{
public:
    using Iterator = std::vector<uint8_t>::const_iterator;

    template<typename T>
    inline static size_t padding(size_t count)
    {
        return (sizeof(T) - count % sizeof(T)) % sizeof(T);
    }

    inline Bytes() {}

    inline Bytes(size_t count) : data(count) {}

    inline Bytes(
        std::initializer_list<uint8_t> elements
    ) : data(elements) {}

    inline Bytes(
        const uint8_t *begin,
        const uint8_t *end
    ) : data(begin, end) {}

    inline Bytes(
        const Bytes &bytes,
        size_t skipBegin,
        size_t skipEnd = 0
    ) : data(bytes.data.begin() + skipBegin, bytes.data.end() - skipEnd) {}

    inline Iterator begin() const
    {
        return data.begin();
    }

    inline Iterator end() const
    {
        return data.end();
    }

    inline size_t size() const
    {
        return data.size();
    }

    inline const uint8_t* raw() const
    {
        return data.data();
    }

    inline uint8_t* raw()
    {
        return data.data();
    }

    template<typename T>
    inline const T* toStruct(size_t offset = 0) const
    {
        return reinterpret_cast<const T*>(data.data() + offset);
    }

    inline void append(const Bytes &bytes)
    {
        data.insert(data.end(), bytes.data.begin(), bytes.data.end());
    }

    inline void append(Iterator begin, Iterator end)
    {
        data.insert(data.end(), begin, end);
    }

    template<typename T>
    inline void append(const T &object, size_t size = sizeof(T))
    {
        data.insert(
            data.end(),
            reinterpret_cast<const uint8_t*>(&object),
            reinterpret_cast<const uint8_t*>(&object) + size
        );
    }

    template<typename T>
    inline void copy(T &destination) const
    {
        std::copy(data.begin(), data.end(), std::begin(destination));
    }

    inline void pad(size_t count)
    {
        data.insert(data.end(), count, 0);
    }

    inline void clear()
    {
        data.clear();
    }

    inline uint8_t operator[](size_t index) const
    {
        return data[index];
    }

    inline uint8_t& operator[](size_t index)
    {
        return data[index];
    }

    inline bool operator==(const Bytes &other) const
    {
        return data == other.data;
    }

    inline bool operator!=(const Bytes &other) const
    {
        return data != other.data;
    }

private:
    std::vector<uint8_t> data;
};

/*
 * Simple wrapper for byte arrays
 * Provides utility functions
 */
template<size_t S>
class FixedBytes
{
public:
    inline Bytes toBytes(size_t count) const
    {
        return Bytes(
            data.begin(),
            data.begin() + count
        );
    }

    inline size_t size() const
    {
        return data.size();
    }

    inline uint8_t* raw()
    {
        return data.data();
    }

private:
    std::array<uint8_t, S> data;
};
