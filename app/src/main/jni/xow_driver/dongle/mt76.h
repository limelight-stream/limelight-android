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

#include "usb.h"

#include <cstdint>
#include <functional>
#include <string>

// Endpoint numbers for reading and writing
// WLAN packets use a separate endpoint
#define MT_EP_READ 5
#define MT_EP_READ_PACKET 4
#define MT_EP_WRITE 4

// Maximum number of WCIDs
#define MT_WCID_COUNT 16

// WLAN frame types
#define MT_WLAN_MANAGEMENT 0x00
#define MT_WLAN_DATA 0x02

// WLAN frame subtypes
#define MT_WLAN_ASSOCIATION_REQ 0x00
#define MT_WLAN_ASSOCIATION_RESP 0x01
#define MT_WLAN_DISASSOCIATION 0x0a
#define MT_WLAN_RESERVED 0x07
#define MT_WLAN_BEACON 0x08
#define MT_WLAN_QOS_DATA 0x08

/*
 * Interfaces with the MT76 chip
 * Handles basic 802.11 client operations
 * The MT76 supports the following channels:
 * 2.4 GHz: 1, 6, 11
 * 5 GHz:
 *   - 36, 40, 44, 48
 *   - 52, 56, 60, 64
 *   - 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140
 *   - 149, 153, 157, 161, 165
 */
class Mt76
{
protected:
    enum Qsel
    {
        MT_QSEL_MGMT,
        MT_QSEL_HCCA,
        MT_QSEL_EDCA,
        MT_QSEL_EDCA_2,
    };

    enum PhyType
    {
        MT_PHY_TYPE_CCK,
        MT_PHY_TYPE_OFDM,
        MT_PHY_TYPE_HT,
        MT_PHY_TYPE_HT_GF,
        MT_PHY_TYPE_VHT,
    };

    enum LedMode
    {
        MT_LED_BLINK,
        MT_LED_ON,
        MT_LED_OFF,
    };

    enum McuEventType
    {
        EVT_CMD_DONE,
        EVT_CMD_ERROR,
        EVT_CMD_RETRY,
        EVT_EVENT_PWR_RSP,
        EVT_EVENT_WOW_RSP,
        EVT_EVENT_CARRIER_DETECT_RSP,
        EVT_EVENT_DFS_DETECT_RSP,
        // Pressed the dongle's button
        EVT_BUTTON_PRESS = 0x04,
        // Received a packet from a client
        EVT_PACKET_RX = 0x0c,
        // Lost connection to a client
        EVT_CLIENT_LOST = 0x0e,
    };

    // Specific to the dongle's firmware
    enum McuFwCommand
    {
        FW_MAC_ADDRESS_SET = 0,
        FW_CLIENT_ADD = 1,
        FW_CLIENT_REMOVE = 2,
        FW_CHANNEL_CANDIDATES_SET = 7,
    };

    enum McuChannelBandwidth
    {
        MT_CH_BW_20,
        MT_CH_BW_40,
        MT_CH_BW_80,
    };

    enum McuChannelGroup
    {
        MT_CH_5G_JAPAN,
        MT_CH_5G_UNII_1,
        MT_CH_5G_UNII_2,
        MT_CH_5G_UNII_2E_1,
        MT_CH_5G_UNII_2E_2,
        MT_CH_5G_UNII_3,
    };

    enum McuCalibration
    {
        MCU_CAL_R = 1,
        MCU_CAL_TEMP_SENSOR,
        MCU_CAL_RXDCOC,
        MCU_CAL_RC,
        MCU_CAL_SX_LOGEN,
        MCU_CAL_LC,
        MCU_CAL_TX_LOFT,
        MCU_CAL_TXIQ,
        MCU_CAL_TSSI,
        MCU_CAL_TSSI_COMP,
        MCU_CAL_DPD,
        MCU_CAL_RXIQC_FI,
        MCU_CAL_RXIQC_FD,
        MCU_CAL_PWRON,
        MCU_CAL_TX_SHAPING,
    };

    enum McuEepromMode
    {
        MT_EE_READ,
        MT_EE_PHYSICAL_READ,
    };

    enum McuCrMode
    {
        MT_RF_CR,
        MT_BBP_CR,
        MT_RF_BBP_CR,
        MT_HL_TEMP_CR_UPDATE,
    };

    enum McuPowerMode
    {
        RADIO_OFF = 0x30,
        RADIO_ON = 0x31,
        RADIO_OFF_AUTO_WAKEUP = 0x32,
        RADIO_OFF_ADVANCE = 0x33,
        RADIO_ON_ADVANCE = 0x34,
    };

    enum McuFunction
    {
        Q_SELECT = 1,
        BW_SETTING = 2,
        USB2_SW_DISCONNECT = 2,
        USB3_SW_DISCONNECT = 3,
        LOG_FW_DEBUG_MSG = 4,
        GET_FW_VERSION = 5,
    };

    enum McuCommand
    {
        // Transmits a packet to a client
        CMD_PACKET_TX = 0,
        CMD_FUN_SET_OP = 1,
        CMD_LOAD_CR = 2,
        // Sends an internal command to the firmware
        CMD_INTERNAL_FW_OP = 3,
        CMD_DYNC_VGA_OP = 6,
        CMD_TDLS_CH_SW = 7,
        CMD_BURST_WRITE = 8,
        CMD_READ_MODIFY_WRITE = 9,
        CMD_RANDOM_READ = 10,
        CMD_BURST_READ = 11,
        CMD_RANDOM_WRITE = 12,
        CMD_LED_MODE_OP = 16,
        CMD_POWER_SAVING_OP = 20,
        CMD_WOW_CONFIG = 21,
        CMD_WOW_QUERY = 22,
        CMD_WOW_FEATURE = 24,
        CMD_CARRIER_DETECT_OP = 28,
        CMD_RADOR_DETECT_OP = 29,
        CMD_SWITCH_CHANNEL_OP = 30,
        CMD_CALIBRATION_OP = 31,
        CMD_BEACON_OP = 32,
        CMD_ANTENNA_OP = 33,
    };

    enum TxInfoType
    {
        NORMAL_PACKET,
        CMD_PACKET,
    };

    enum DmaMsgPort
    {
        WLAN_PORT,
        CPU_RX_PORT,
        CPU_TX_PORT,
        CPU_HOST_PORT,
        VIRTUAL_CPU_RX_PORT,
        VIRTUAL_CPU_TX_PORT,
        DISCARD,
    };

    enum VendorRequest
    {
        MT_VEND_DEV_MODE = 0x1,
        MT_VEND_WRITE = 0x2,
        MT_VEND_MULTI_WRITE = 0x6,
        MT_VEND_MULTI_READ = 0x7,
        MT_VEND_READ_EEPROM = 0x9,
        MT_VEND_WRITE_FCE = 0x42,
        MT_VEND_WRITE_CFG = 0x46,
        MT_VEND_READ_CFG = 0x47,
    };

    struct QosFrame
    {
        uint16_t qosControl;
    } __attribute__((packed));

    struct AssociationResponseFrame
    {
        uint16_t capabilityInfo;
        uint16_t statusCode;
        uint16_t associationId;
        uint64_t unknown;
    } __attribute__((packed));

    struct ReservedFrame
    {
        uint8_t unknown;
        uint8_t type;
    } __attribute__((packed));

    struct BeaconFrame
    {
        uint64_t timestamp;
        uint16_t interval;
        uint16_t capabilityInfo;
        uint16_t ssid;
    } __attribute__((packed));

    struct FrameControl
    {
        uint32_t protocolVersion : 2;
        uint32_t type : 2;
        uint32_t subtype : 4;
        uint32_t toDs : 1;
        uint32_t fromDs : 1;
        uint32_t moreFragments : 1;
        uint32_t retry : 1;
        uint32_t powerManagement : 1;
        uint32_t moreData : 1;
        uint32_t protectedFrame : 1;
        uint32_t order : 1;
    } __attribute__((packed));

    struct WlanFrame
    {
        FrameControl frameControl;
        uint16_t duration;

        uint8_t destination[6];
        uint8_t source[6];
        uint8_t bssId[6];

        uint16_t sequenceControl;
    } __attribute__((packed));

    // Receive wireless information
    struct RxWi
    {
        // RX descriptor
        uint32_t dmaLength;

        uint32_t wcid : 8;
        uint32_t keyIndex : 2;
        uint32_t bssIndex : 3;
        uint32_t userDefined : 3;
        uint32_t mpduByteCount : 14;
        uint32_t reserved1 : 1;
        uint32_t eof : 1;

        uint32_t trafficId : 4;
        uint32_t sequenceNumber : 12;
        uint32_t mcs : 6;
        uint32_t ldpc : 1;
        uint32_t bandwidth : 2;
        uint32_t sgi : 1;
        uint32_t stbc : 1;
        uint32_t ldpcExSym : 1;
        uint32_t reserved2 : 1;
        uint32_t phyType : 3;

        uint8_t rssi[4];

        uint8_t bbpRxInfo[16];
    } __attribute__((packed));

    // Transmit wireless information
    struct TxWi
    {
        uint32_t fragment : 1;
        uint32_t mimoPowerSave : 1;
        uint32_t cfAck : 1;
        uint32_t timestamp : 1;
        uint32_t ampdu : 1;
        uint32_t mpduDensity : 3;
        uint32_t txop : 2;
        uint32_t ndpSoundingRate : 1;
        uint32_t rtsBwSig : 1;
        uint32_t ndpSoundingBw : 2;
        uint32_t sounding : 1;
        uint32_t lutEnable : 1;
        uint32_t mcs : 6;
        uint32_t ldpc : 1;
        uint32_t bandwidth : 2;
        uint32_t sgi : 1;
        uint32_t stbc : 1;
        uint32_t eTxBf : 1;
        uint32_t iTxBf : 1;
        uint32_t phyType : 3;

        uint32_t ack : 1;
        uint32_t nseq : 1;
        uint32_t baWindowSize : 6;
        uint32_t wcid : 8;
        uint32_t mpduByteCount : 14;
        uint32_t txbfPtSca : 1;
        uint32_t tim : 1;

        uint32_t iv;

        uint32_t eiv;

        uint32_t eapId : 8;
        uint32_t streamMode : 8;
        uint32_t powerAdjustment : 4;
        uint32_t reserved : 3;
        uint32_t groupId : 1;
        uint32_t packetId : 8;
    } __attribute__((packed));

    // Used to differentiate between ports
    struct RxInfoGeneric
    {
        uint32_t data : 25;
        uint32_t qsel : 2;
        uint32_t port : 3;
        uint32_t infoType : 2;
    };

    struct RxInfoCommand
    {
        uint32_t length : 14;
        uint32_t reserved : 1;
        uint32_t selfGen : 1;
        uint32_t sequence : 4;
        uint32_t eventType : 4;
        uint32_t pcieInterrupt : 1;
        uint32_t qsel : 2;
        uint32_t port : 3;
        uint32_t infoType : 2;
    } __attribute__((packed));

    struct RxInfoPacket
    {
        uint32_t length : 14;
        uint32_t reserved : 2;
        uint32_t udpError : 1;
        uint32_t tcpError : 1;
        uint32_t ipError : 1;
        uint32_t is80211 : 1;
        uint32_t l3l4Done : 1;
        uint32_t macLength : 3;
        uint32_t pcieInterrupt : 1;
        uint32_t qsel : 2;
        uint32_t port : 3;
        uint32_t infoType : 2;
    } __attribute__((packed));

    struct TxInfoCommand
    {
        uint32_t length : 16;
        uint32_t sequence : 4;
        uint32_t command : 7;
        uint32_t port : 3;
        uint32_t infoType : 2;
    } __attribute__((packed));

    struct TxInfoPacket
    {
        uint32_t length : 16;
        uint32_t nextVld : 1;
        uint32_t txBurst : 1;
        uint32_t reserved1 : 1;
        uint32_t is80211 : 1;
        uint32_t tso : 1;
        uint32_t cso : 1;
        uint32_t reserved2 : 2;
        uint32_t wiv : 1;
        uint32_t qsel : 2;
        uint32_t port : 3;
        uint32_t infoType : 2;
    } __attribute__((packed));

    union BeaconTimeConfig
    {
        struct
        {
            uint32_t interval : 16;
            uint32_t tsfTimerEnabled : 1;
            uint32_t tsfSyncMode : 2;
            uint32_t tbttTimerEnabled : 1;
            uint32_t transmitBeacon : 1;
            uint32_t reserved : 3;
            uint32_t tsfInsertionCompensation : 8;
        } __attribute__((packed)) props;

        uint32_t value;
    };

    struct ChannelConfigData
    {
        uint8_t channel;
        uint8_t padding1;
        uint16_t padding2;
        uint16_t txRxSetting;
        uint16_t padding3;
        uint64_t padding4;
        uint8_t bandwidth;
        uint8_t txPower;
        uint8_t scan;
        uint8_t unknown;
    } __attribute__((packed));

    union EfuseControl
    {
        struct
        {
            uint32_t addressOut : 6;
            uint32_t mode : 2;
            uint32_t ldoOffTime : 6;
            uint32_t ldoOnTime : 2;
            uint32_t addressIn : 10;
            uint32_t reserved : 4;
            uint32_t kick : 1;
            uint32_t select : 1;
        } __attribute__((packed)) props;

        uint32_t value;
    };

    struct FwHeader
    {
        uint32_t ilmLength;
        uint32_t dlmLength;
        uint16_t buildVersion;
        uint16_t firmwareVersion;
        uint32_t padding;
        char buildTime[16];
    } __attribute__((packed));

    union DmaConfig
    {
        struct
        {
            uint32_t rxBulkAggTimeout : 8;
            uint32_t rxBulkAggLimit : 8;
            uint32_t udmaTxWlDrop : 1;
            uint32_t wakeupEnabled : 1;
            uint32_t rxDropOrPad : 1;
            uint32_t txClear : 1;
            uint32_t txopHalt : 1;
            uint32_t rxBulkAggEnabled : 1;
            uint32_t rxBulkEnabled : 1;
            uint32_t txBulkEnabled : 1;
            uint32_t epOutValid : 6;
            uint32_t rxBusy : 1;
            uint32_t txBusy : 1;
        } __attribute__((packed)) props;

        uint32_t value;
    };

    Mt76(std::unique_ptr<UsbDevice> usbDevice);
    virtual ~Mt76();

    bool init(std::string firmwarePath);

    /* WLAN client operations */
    uint8_t associateClient(Bytes address);
    bool removeClient(uint8_t wcid);
    bool pairClient(Bytes address);
    bool sendClientPacket(
        uint8_t wcid,
        Bytes address,
        const Bytes &packet
    );

    /* MCU functions/commands */
    bool setPairingStatus(bool enable);

    Bytes macAddress;
    std::unique_ptr<UsbDevice> usbDevice;

private:
    /* Packet transmission */
    bool sendWlanPacket(const Bytes &packet);

    /* Initialization routines */
    bool initRegisters();
    bool calibrateCrystal();
    bool initChannels();
    bool loadFirmware(std::string firmwarePath);
    bool loadFirmwarePart(
        uint32_t offset,
        Bytes::Iterator start,
        Bytes::Iterator end
    );

    /* MCU functions/commands */
    bool writeBeacon(bool pairing);
    bool selectFunction(McuFunction function, uint32_t value);
    bool powerMode(McuPowerMode mode);
    bool loadCr(McuCrMode mode);
    bool burstWrite(uint32_t index, const Bytes &values);
    bool calibrate(McuCalibration calibration, uint32_t value);
    bool configureChannel(
        uint8_t channel,
        McuChannelBandwidth bandwidth,
        bool scan
    );
    uint8_t getChannelPower(uint8_t channel);
    uint8_t getChannelGroup(uint8_t channel);
    uint8_t getChannelSubgroup(uint8_t channel);
    bool sendFirmwareCommand(McuFwCommand command, const Bytes &data);
    bool setLedMode(uint32_t index);
    bool sendCommand(McuCommand command, const Bytes &data);

    /* USB/MCU communication/utilities */
    bool pollTimeout(std::function<bool()> condition);
    uint32_t controlRead(
        uint16_t address,
        VendorRequest request = MT_VEND_MULTI_READ
    );
    void controlWrite(
        uint16_t address,
        uint32_t value,
        VendorRequest request = MT_VEND_MULTI_WRITE
    );
    Bytes efuseRead(uint8_t address, uint8_t index);
    uint16_t connectedClients = 0;
};

class Mt76Exception : public std::runtime_error
{
public:
    Mt76Exception(std::string message);
};
