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

#include "../utils/bytes.h"

#include <libusb/libusb.h>
#include <jni.h>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <stdexcept>

#define USB_MAX_BULK_TRANSFER_SIZE 512

/*
 * Base class for interfacing with USB devices
 * Provides control/bulk transfer capabilities
 */
class UsbDevice
{
public:

    struct ControlPacket
    {
        uint8_t request;
        uint16_t value;
        uint16_t index;
        uint8_t *data;
        uint16_t length;
    };

    UsbDevice(int fd);
    virtual ~UsbDevice();

    void controlTransfer(ControlPacket packet, bool write);
    int bulkRead(
        uint8_t endpoint,
        FixedBytes<USB_MAX_BULK_TRANSFER_SIZE> &buffer
    );
    bool bulkWrite(uint8_t endpoint, Bytes &data);

private:
    libusb_context *ctx;
    libusb_device_handle *handle;
};
