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


#include <libusb/libusb.h>
#include "usb.h"
#include "../utils/log.h"

// Timeouts in milliseconds
#define USB_TIMEOUT_READ 1000
#define USB_TIMEOUT_WRITE 1000
UsbDevice::UsbDevice(
        int fd
)
{
    libusb_context *ctx = NULL;
    libusb_device_handle *devh = NULL;
    int r = 0;
    r = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, NULL);
    if (r != LIBUSB_SUCCESS) {
        Log::error("libusb_set_option failed: %d\n", r);
        return;
    }
    r = libusb_init(&ctx);
    if (r < 0) {
        Log::error("libusb_init failed: %d\n", r);
        return;
    }
    r = libusb_wrap_sys_device(ctx, (intptr_t)fd, &devh);
    if (r < 0) {
        Log::error("libusb_wrap_sys_device failed: %d\n", r);
        return;
    } else if (devh == NULL) {
        Log::error("libusb_wrap_sys_device returned invalid handle\n");
        return;
    }
    this->handle = devh;
    this->ctx = ctx;

    r = libusb_wrap_sys_device(NULL, (intptr_t)fd, &devh);
    if (r) {
        Log::error("Error libusb_wrap_sys_device");
        return;
    }

    r = libusb_reset_device(devh);

    if (r) {
        Log::error("Error resetting device");
        return;
    }

    r = libusb_set_configuration(handle, 1);

    if (r) {
        Log::error("Error setting configuration");
    }

    r = libusb_claim_interface(handle, 0);

    if (r) {
        Log::error("Error claiming interface");
    }
}


UsbDevice::~UsbDevice()
{
    Log::debug("Closing device...");

    int error = libusb_release_interface(handle, 0);

    if (error)
    {
        Log::error(
                "Error releasing interface: %s",
                libusb_error_name(error)
        );
    }

    libusb_close(handle);
}

void UsbDevice::controlTransfer(ControlPacket packet, bool write)
{
    uint8_t direction = write ? LIBUSB_ENDPOINT_OUT : LIBUSB_ENDPOINT_IN;

    // Number of bytes or error code
    int transferred = libusb_control_transfer(
            handle,
            LIBUSB_REQUEST_TYPE_VENDOR | LIBUSB_RECIPIENT_DEVICE | direction,
            packet.request,
            packet.value,
            packet.index,
            packet.data,
            packet.length,
            USB_TIMEOUT_WRITE
    );

    if (transferred != packet.length)
    {
        Log::error(
                "Error in control transfer: %s",
                libusb_error_name(transferred)
        );
    }
}

int UsbDevice::bulkRead(
        uint8_t endpoint,
        FixedBytes<USB_MAX_BULK_TRANSFER_SIZE> &buffer
) {
    int transferred = 0;
    int error = libusb_bulk_transfer(
            handle,
            endpoint | LIBUSB_ENDPOINT_IN,
            buffer.raw(),
            buffer.size(),
            &transferred,
            USB_TIMEOUT_READ
    );

    if (error && error != LIBUSB_ERROR_TIMEOUT)
    {
        Log::error("Error in bulk read: %s", libusb_error_name(error));

        return -1;
    }

    return transferred;
}

bool UsbDevice::bulkWrite(uint8_t endpoint, Bytes &data)
{
    int transferred = 0;
    int error = libusb_bulk_transfer(
            handle,
            endpoint | LIBUSB_ENDPOINT_OUT,
            data.raw(),
            data.size(),
            &transferred,
            USB_TIMEOUT_WRITE
    );

    if (error)
    {
        Log::error("Error in bulk write: %s", libusb_error_name(error));

        return false;
    }

    return true;
}
