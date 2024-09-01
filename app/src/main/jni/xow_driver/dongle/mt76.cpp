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

#include "mt76.h"
#include "../utils/log.h"

#include <chrono>
#include <fstream>

#define BITS_PER_LONG (sizeof(long) * 8)
#define BIT(nr) (1UL << (nr))
#define GENMASK(h, l) ((~0UL - (1UL << l) + 1) & (~0UL >> (BITS_PER_LONG - 1 - h)))

/*
 * Most of the defines and enums were copied from OpenWrt's mt76 driver.
 * Link to the repository: https://github.com/openwrt/mt76.
 * The source code is released under the ISC license:
 *
 * Copyright (C) Felix Fietkau <nbd@nbd.name>
 * Copyright (C) Lorenzo Bianconi <lorenzo.bianconi83@gmail.com>
 * Copyright (C) Stanislaw Gruszka <stf_xl@wp.pl>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHORS DISCLAIM ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION
 * OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
 * CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
#define MT_ASIC_VERSION 0x0000

#define MT76XX_REV_E3 0x22
#define MT76XX_REV_E4 0x33

#define MT_CMB_CTRL 0x0020
#define MT_CMB_CTRL_XTAL_RDY BIT(22)
#define MT_CMB_CTRL_PLL_LD BIT(23)

#define MT_EFUSE_CTRL 0x0024
#define MT_EFUSE_CTRL_AOUT GENMASK(5, 0)
#define MT_EFUSE_CTRL_MODE GENMASK(7, 6)
#define MT_EFUSE_CTRL_LDO_OFF_TIME GENMASK(13, 8)
#define MT_EFUSE_CTRL_LDO_ON_TIME GENMASK(15, 14)
#define MT_EFUSE_CTRL_AIN GENMASK(25, 16)
#define MT_EFUSE_CTRL_KICK BIT(30)
#define MT_EFUSE_CTRL_SEL BIT(31)

#define MT_EFUSE_DATA_BASE 0x0028
#define MT_EFUSE_DATA(_n) (MT_EFUSE_DATA_BASE + ((_n) << 2))

#define MT_COEXCFG0 0x0040
#define MT_COEXCFG0_COEX_EN BIT(0)

#define MT_WLAN_FUN_CTRL 0x0080
#define MT_WLAN_FUN_CTRL_WLAN_EN BIT(0)
#define MT_WLAN_FUN_CTRL_WLAN_CLK_EN BIT(1)
#define MT_WLAN_FUN_CTRL_WLAN_RESET_RF BIT(2)

#define MT_COEXCFG3 0x004c

#define MT_LDO_CTRL_0 0x006c
#define MT_LDO_CTRL_1 0x0070

#define MT_WLAN_FUN_CTRL_CSR_F20M_CKEN BIT(3)

#define MT_WLAN_FUN_CTRL_PCIE_CLK_REQ BIT(4)
#define MT_WLAN_FUN_CTRL_FRC_WL_ANT_SEL BIT(5)
#define MT_WLAN_FUN_CTRL_INV_ANT_SEL BIT(6)
#define MT_WLAN_FUN_CTRL_WAKE_HOST BIT(7)

#define MT_WLAN_FUN_CTRL_THERM_RST BIT(8)
#define MT_WLAN_FUN_CTRL_THERM_CKEN BIT(9)

#define MT_XO_CTRL0 0x0100
#define MT_XO_CTRL1 0x0104
#define MT_XO_CTRL2 0x0108
#define MT_XO_CTRL3 0x010c
#define MT_XO_CTRL4 0x0110

#define MT_XO_CTRL5 0x0114
#define MT_XO_CTRL5_C2_VAL GENMASK(14, 8)

#define MT_XO_CTRL6 0x0118
#define MT_XO_CTRL6_C2_CTRL GENMASK(14, 8)

#define MT_XO_CTRL7 0x011c

#define MT_IOCFG_6 0x0124

#define MT_USB_U3DMA_CFG 0x9018
#define MT_USB_DMA_CFG_RX_BULK_AGG_TOUT GENMASK(7, 0)
#define MT_USB_DMA_CFG_RX_BULK_AGG_LMT GENMASK(15, 8)
#define MT_USB_DMA_CFG_UDMA_TX_WL_DROP BIT(16)
#define MT_USB_DMA_CFG_WAKE_UP_EN BIT(17)
#define MT_USB_DMA_CFG_RX_DROP_OR_PAD BIT(18)
#define MT_USB_DMA_CFG_TX_CLR BIT(19)
#define MT_USB_DMA_CFG_TXOP_HALT BIT(20)
#define MT_USB_DMA_CFG_RX_BULK_AGG_EN BIT(21)
#define MT_USB_DMA_CFG_RX_BULK_EN BIT(22)
#define MT_USB_DMA_CFG_TX_BULK_EN BIT(23)
#define MT_USB_DMA_CFG_EP_OUT_VALID GENMASK(29, 24)
#define MT_USB_DMA_CFG_RX_BUSY BIT(30)
#define MT_USB_DMA_CFG_TX_BUSY BIT(31)

#define MT_WLAN_MTC_CTRL 0x10148
#define MT_WLAN_MTC_CTRL_MTCMOS_PWR_UP BIT(0)
#define MT_WLAN_MTC_CTRL_PWR_ACK BIT(12)
#define MT_WLAN_MTC_CTRL_PWR_ACK_S BIT(13)
#define MT_WLAN_MTC_CTRL_BBP_MEM_PD GENMASK(19, 16)
#define MT_WLAN_MTC_CTRL_PBF_MEM_PD BIT(20)
#define MT_WLAN_MTC_CTRL_FCE_MEM_PD BIT(21)
#define MT_WLAN_MTC_CTRL_TSO_MEM_PD BIT(22)
#define MT_WLAN_MTC_CTRL_BBP_MEM_RB BIT(24)
#define MT_WLAN_MTC_CTRL_PBF_MEM_RB BIT(25)
#define MT_WLAN_MTC_CTRL_FCE_MEM_RB BIT(26)
#define MT_WLAN_MTC_CTRL_TSO_MEM_RB BIT(27)
#define MT_WLAN_MTC_CTRL_STATE_UP BIT(28)

#define MT_INT_SOURCE_CSR 0x0200
#define MT_INT_MASK_CSR 0x0204

#define MT_INT_RX_DONE(_n) BIT(_n)
#define MT_INT_RX_DONE_ALL GENMASK(1, 0)
#define MT_INT_TX_DONE_ALL GENMASK(13, 4)
#define MT_INT_TX_DONE(_n) BIT((_n) + 4)
#define MT_INT_RX_COHERENT BIT(16)
#define MT_INT_TX_COHERENT BIT(17)
#define MT_INT_ANY_COHERENT BIT(18)
#define MT_INT_MCU_CMD BIT(19)
#define MT_INT_TBTT BIT(20)
#define MT_INT_PRE_TBTT BIT(21)
#define MT_INT_TX_STAT BIT(22)
#define MT_INT_AUTO_WAKEUP BIT(23)
#define MT_INT_GPTIMER BIT(24)
#define MT_INT_RXDELAYINT BIT(26)
#define MT_INT_TXDELAYINT BIT(27)

#define MT_WPDMA_GLO_CFG 0x0208
#define MT_WPDMA_GLO_CFG_TX_DMA_EN BIT(0)
#define MT_WPDMA_GLO_CFG_TX_DMA_BUSY BIT(1)
#define MT_WPDMA_GLO_CFG_RX_DMA_EN BIT(2)
#define MT_WPDMA_GLO_CFG_RX_DMA_BUSY BIT(3)
#define MT_WPDMA_GLO_CFG_DMA_BURST_SIZE GENMASK(5, 4)
#define MT_WPDMA_GLO_CFG_TX_WRITEBACK_DONE BIT(6)
#define MT_WPDMA_GLO_CFG_BIG_ENDIAN BIT(7)
#define MT_WPDMA_GLO_CFG_HDR_SEG_LEN GENMASK(15, 8)
#define MT_WPDMA_GLO_CFG_CLK_GATE_DIS BIT(30)
#define MT_WPDMA_GLO_CFG_RX_2B_OFFSET BIT(31)

#define MT_WPDMA_RST_IDX 0x020c

#define MT_WPDMA_DELAY_INT_CFG 0x0210

#define MT_WMM_AIFSN 0x0214
#define MT_WMM_AIFSN_MASK GENMASK(3, 0)
#define MT_WMM_AIFSN_SHIFT(_n) ((_n) * 4)

#define MT_WMM_CWMIN 0x0218
#define MT_WMM_CWMIN_MASK GENMASK(3, 0)
#define MT_WMM_CWMIN_SHIFT(_n) ((_n) * 4)

#define MT_WMM_CWMAX 0x021c
#define MT_WMM_CWMAX_MASK GENMASK(3, 0)
#define MT_WMM_CWMAX_SHIFT(_n) ((_n) * 4)

#define MT_WMM_TXOP_BASE 0x0220
#define MT_WMM_TXOP(_n) (MT_WMM_TXOP_BASE + (((_n) / 2) << 2))
#define MT_WMM_TXOP_SHIFT(_n) (((_n) & 1) * 16)
#define MT_WMM_TXOP_MASK GENMASK(15, 0)

#define MT_FCE_DMA_ADDR 0x0230
#define MT_FCE_DMA_LEN 0x0234
#define MT_USB_DMA_CFG 0x0238

#define MT_TSO_CTRL 0x0250
#define MT_HEADER_TRANS_CTRL_REG 0x0260

#define MT_US_CYC_CFG 0x02a4
#define MT_US_CYC_CNT GENMASK(7, 0)

#define MT_TX_RING_BASE 0x0300
#define MT_RX_RING_BASE 0x03c0

#define MT_TX_HW_QUEUE_MCU 8
#define MT_TX_HW_QUEUE_MGMT 9

#define MT_PBF_SYS_CTRL 0x0400
#define MT_PBF_SYS_CTRL_MCU_RESET BIT(0)
#define MT_PBF_SYS_CTRL_DMA_RESET BIT(1)
#define MT_PBF_SYS_CTRL_MAC_RESET BIT(2)
#define MT_PBF_SYS_CTRL_PBF_RESET BIT(3)
#define MT_PBF_SYS_CTRL_ASY_RESET BIT(4)

#define MT_PBF_CFG 0x0404
#define MT_PBF_CFG_TX0Q_EN BIT(0)
#define MT_PBF_CFG_TX1Q_EN BIT(1)
#define MT_PBF_CFG_TX2Q_EN BIT(2)
#define MT_PBF_CFG_TX3Q_EN BIT(3)
#define MT_PBF_CFG_RX0Q_EN BIT(4)
#define MT_PBF_CFG_RX_DROP_EN BIT(8)

#define MT_PBF_TX_MAX_PCNT 0x0408
#define MT_PBF_RX_MAX_PCNT 0x040c

#define MT_BCN_OFFSET_BASE 0x041c
#define MT_BCN_OFFSET(_n) (MT_BCN_OFFSET_BASE + ((_n) << 2))

#define MT_RXQ_STA 0x0430
#define MT_TXQ_STA 0x0434
#define MT_RF_CSR_CFG 0x0500
#define MT_RF_CSR_CFG_DATA GENMASK(7, 0)
#define MT_RF_CSR_CFG_REG_ID GENMASK(14, 8)
#define MT_RF_CSR_CFG_REG_BANK GENMASK(17, 15)
#define MT_RF_CSR_CFG_WR BIT(30)
#define MT_RF_CSR_CFG_KICK BIT(31)

#define MT_RF_BYPASS_0 0x0504
#define MT_RF_BYPASS_1 0x0508
#define MT_RF_SETTING_0 0x050c

#define MT_RF_MISC 0x0518
#define MT_RF_DATA_WRITE 0x0524

#define MT_RF_CTRL 0x0528
#define MT_RF_CTRL_ADDR GENMASK(11, 0)
#define MT_RF_CTRL_WRITE BIT(12)
#define MT_RF_CTRL_BUSY BIT(13)
#define MT_RF_CTRL_IDX BIT(16)

#define MT_RF_DATA_READ 0x052c

#define MT_COM_REG0 0x0730
#define MT_COM_REG1 0x0734
#define MT_COM_REG2 0x0738
#define MT_COM_REG3 0x073C

#define MT_LED_CTRL 0x0770
#define MT_LED_CTRL_REPLAY(_n) BIT(0 + (8 * (_n)))
#define MT_LED_CTRL_POLARITY(_n) BIT(1 + (8 * (_n)))
#define MT_LED_CTRL_TX_BLINK_MODE(_n) BIT(2 + (8 * (_n)))
#define MT_LED_CTRL_KICK(_n) BIT(7 + (8 * (_n)))

#define MT_LED_TX_BLINK_0 0x0774
#define MT_LED_TX_BLINK_1 0x0778

#define MT_LED_S0_BASE 0x077C
#define MT_LED_S0(_n) (MT_LED_S0_BASE + 8 * (_n))
#define MT_LED_S1_BASE 0x0780
#define MT_LED_S1(_n) (MT_LED_S1_BASE + 8 * (_n))
#define MT_LED_STATUS_OFF GENMASK(31, 24)
#define MT_LED_STATUS_ON GENMASK(23, 16)
#define MT_LED_STATUS_DURATION GENMASK(15, 8)

#define MT_FCE_PSE_CTRL 0x0800
#define MT_FCE_PARAMETERS 0x0804
#define MT_FCE_CSO 0x0808

#define MT_FCE_L2_STUFF 0x080c
#define MT_FCE_L2_STUFF_HT_L2_EN BIT(0)
#define MT_FCE_L2_STUFF_QOS_L2_EN BIT(1)
#define MT_FCE_L2_STUFF_RX_STUFF_EN BIT(2)
#define MT_FCE_L2_STUFF_TX_STUFF_EN BIT(3)
#define MT_FCE_L2_STUFF_WR_MPDU_LEN_EN BIT(4)
#define MT_FCE_L2_STUFF_MVINV_BSWAP BIT(5)
#define MT_FCE_L2_STUFF_TS_CMD_QSEL_EN GENMASK(15, 8)
#define MT_FCE_L2_STUFF_TS_LEN_EN GENMASK(23, 16)
#define MT_FCE_L2_STUFF_OTHER_PORT GENMASK(25, 24)

#define MT_FCE_WLAN_FLOW_CONTROL1 0x0824

#define MT_TX_CPU_FROM_FCE_BASE_PTR 0x09a0
#define MT_TX_CPU_FROM_FCE_MAX_COUNT 0x09a4
#define MT_TX_CPU_FROM_FCE_CPU_DESC_IDX 0x09a8
#define MT_FCE_PDMA_GLOBAL_CONF 0x09c4
#define MT_FCE_SKIP_FS 0x0a6c

#define MT_PAUSE_ENABLE_CONTROL1 0x0a38

#define MT_MAC_CSR0 0x1000

#define MT_MAC_SYS_CTRL 0x1004
#define MT_MAC_SYS_CTRL_RESET_CSR BIT(0)
#define MT_MAC_SYS_CTRL_RESET_BBP BIT(1)
#define MT_MAC_SYS_CTRL_ENABLE_TX BIT(2)
#define MT_MAC_SYS_CTRL_ENABLE_RX BIT(3)

#define MT_MAC_ADDR_DW0 0x1008
#define MT_MAC_ADDR_DW1 0x100c
#define MT_MAC_ADDR_DW1_U2ME_MASK GENMASK(23, 16)

#define MT_MAC_BSSID_DW0 0x1010
#define MT_MAC_BSSID_DW1 0x1014
#define MT_MAC_BSSID_DW1_ADDR GENMASK(15, 0)
#define MT_MAC_BSSID_DW1_MBSS_MODE GENMASK(17, 16)
#define MT_MAC_BSSID_DW1_MBEACON_N GENMASK(20, 18)
#define MT_MAC_BSSID_DW1_MBSS_LOCAL_BIT BIT(21)
#define MT_MAC_BSSID_DW1_MBSS_MODE_B2 BIT(22)
#define MT_MAC_BSSID_DW1_MBEACON_N_B3 BIT(23)
#define MT_MAC_BSSID_DW1_MBSS_IDX_BYTE GENMASK(26, 24)

#define MT_MAX_LEN_CFG 0x1018
#define MT_MAX_LEN_CFG_AMPDU GENMASK(13, 12)

#define MT_LED_CFG 0x102c

#define MT_AMPDU_MAX_LEN_20M1S 0x1030
#define MT_AMPDU_MAX_LEN_20M2S 0x1034
#define MT_AMPDU_MAX_LEN_40M1S 0x1038
#define MT_AMPDU_MAX_LEN_40M2S 0x103c
#define MT_AMPDU_MAX_LEN 0x1040

#define MT_WCID_DROP_BASE 0x106c
#define MT_WCID_DROP(_n) (MT_WCID_DROP_BASE + ((_n) >> 5) * 4)
#define MT_WCID_DROP_MASK(_n) BIT((_n) % 32)

#define MT_BCN_BYPASS_MASK 0x108c

#define MT_MAC_APC_BSSID_BASE 0x1090
#define MT_MAC_APC_BSSID_L(_n) (MT_MAC_APC_BSSID_BASE + ((_n) * 8))
#define MT_MAC_APC_BSSID_H(_n) (MT_MAC_APC_BSSID_BASE + ((_n) * 8 + 4))
#define MT_MAC_APC_BSSID_H_ADDR GENMASK(15, 0)
#define MT_MAC_APC_BSSID0_H_EN BIT(16)

#define MT_XIFS_TIME_CFG 0x1100
#define MT_XIFS_TIME_CFG_CCK_SIFS GENMASK(7, 0)
#define MT_XIFS_TIME_CFG_OFDM_SIFS GENMASK(15, 8)
#define MT_XIFS_TIME_CFG_OFDM_XIFS GENMASK(19, 16)
#define MT_XIFS_TIME_CFG_EIFS GENMASK(28, 20)
#define MT_XIFS_TIME_CFG_BB_RXEND_EN BIT(29)

#define MT_BKOFF_SLOT_CFG 0x1104
#define MT_BKOFF_SLOT_CFG_SLOTTIME GENMASK(7, 0)
#define MT_BKOFF_SLOT_CFG_CC_DELAY GENMASK(11, 8)

#define MT_CH_TIME_CFG 0x110c
#define MT_CH_TIME_CFG_TIMER_EN BIT(0)
#define MT_CH_TIME_CFG_TX_AS_BUSY BIT(1)
#define MT_CH_TIME_CFG_RX_AS_BUSY BIT(2)
#define MT_CH_TIME_CFG_NAV_AS_BUSY BIT(3)
#define MT_CH_TIME_CFG_EIFS_AS_BUSY BIT(4)
#define MT_CH_TIME_CFG_MDRDY_CNT_EN BIT(5)
#define MT_CH_CCA_RC_EN BIT(6)
#define MT_CH_TIME_CFG_CH_TIMER_CLR GENMASK(9, 8)
#define MT_CH_TIME_CFG_MDRDY_CLR GENMASK(11, 10)

#define MT_PBF_LIFE_TIMER 0x1110

#define MT_BEACON_TIME_CFG 0x1114
#define MT_BEACON_TIME_CFG_INTVAL GENMASK(15, 0)
#define MT_BEACON_TIME_CFG_TIMER_EN BIT(16)
#define MT_BEACON_TIME_CFG_SYNC_MODE GENMASK(18, 17)
#define MT_BEACON_TIME_CFG_TBTT_EN BIT(19)
#define MT_BEACON_TIME_CFG_BEACON_TX BIT(20)
#define MT_BEACON_TIME_CFG_TSF_COMP GENMASK(31, 24)

#define MT_TBTT_SYNC_CFG 0x1118
#define MT_TSF_TIMER_DW0 0x111c
#define MT_TSF_TIMER_DW1 0x1120
#define MT_TBTT_TIMER 0x1124
#define MT_TBTT_TIMER_VAL GENMASK(16, 0)

#define MT_INT_TIMER_CFG 0x1128
#define MT_INT_TIMER_CFG_PRE_TBTT GENMASK(15, 0)
#define MT_INT_TIMER_CFG_GP_TIMER GENMASK(31, 16)

#define MT_INT_TIMER_EN 0x112c
#define MT_INT_TIMER_EN_PRE_TBTT_EN BIT(0)
#define MT_INT_TIMER_EN_GP_TIMER_EN BIT(1)

#define MT_CH_IDLE 0x1130
#define MT_CH_BUSY 0x1134
#define MT_EXT_CH_BUSY 0x1138
#define MT_ED_CCA_TIMER 0x1140

#define MT_MAC_STATUS 0x1200
#define MT_MAC_STATUS_TX BIT(0)
#define MT_MAC_STATUS_RX BIT(1)

#define MT_PWR_PIN_CFG 0x1204
#define MT_AUX_CLK_CFG 0x120c

#define MT_BB_PA_MODE_CFG0 0x1214
#define MT_BB_PA_MODE_CFG1 0x1218
#define MT_RF_PA_MODE_CFG0 0x121c
#define MT_RF_PA_MODE_CFG1 0x1220

#define MT_RF_PA_MODE_ADJ0 0x1228
#define MT_RF_PA_MODE_ADJ1 0x122c

#define MT_DACCLK_EN_DLY_CFG 0x1264

#define MT_EDCA_CFG_BASE 0x1300
#define MT_EDCA_CFG_AC(_n) (MT_EDCA_CFG_BASE + ((_n) << 2))
#define MT_EDCA_CFG_TXOP GENMASK(7, 0)
#define MT_EDCA_CFG_AIFSN GENMASK(11, 8)
#define MT_EDCA_CFG_CWMIN GENMASK(15, 12)
#define MT_EDCA_CFG_CWMAX GENMASK(19, 16)

#define MT_TX_PWR_CFG_0 0x1314
#define MT_TX_PWR_CFG_1 0x1318
#define MT_TX_PWR_CFG_2 0x131c
#define MT_TX_PWR_CFG_3 0x1320
#define MT_TX_PWR_CFG_4 0x1324
#define MT_TX_PIN_CFG 0x1328
#define MT_TX_PIN_CFG_TXANT GENMASK(3, 0)
#define MT_TX_PIN_CFG_RXANT GENMASK(11, 8)
#define MT_TX_PIN_RFTR_EN BIT(16)
#define MT_TX_PIN_TRSW_EN BIT(18)

#define MT_TX_BAND_CFG 0x132c
#define MT_TX_BAND_CFG_UPPER_40M BIT(0)
#define MT_TX_BAND_CFG_5G BIT(1)
#define MT_TX_BAND_CFG_2G BIT(2)

#define MT_HT_FBK_TO_LEGACY 0x1384
#define MT_TX_MPDU_ADJ_INT 0x1388

#define MT_TX_PWR_CFG_7 0x13d4
#define MT_TX_PWR_CFG_8 0x13d8
#define MT_TX_PWR_CFG_9 0x13dc

#define MT_TX_SW_CFG0 0x1330
#define MT_TX_SW_CFG1 0x1334
#define MT_TX_SW_CFG2 0x1338

#define MT_TXOP_CTRL_CFG 0x1340
#define MT_TXOP_TRUN_EN GENMASK(5, 0)
#define MT_TXOP_EXT_CCA_DLY GENMASK(15, 8)
#define MT_TXOP_ED_CCA_EN BIT(20)

#define MT_TX_RTS_CFG 0x1344
#define MT_TX_RTS_CFG_RETRY_LIMIT GENMASK(7, 0)
#define MT_TX_RTS_CFG_THRESH GENMASK(23, 8)
#define MT_TX_RTS_FALLBACK BIT(24)

#define MT_TX_TIMEOUT_CFG 0x1348
#define MT_TX_TIMEOUT_CFG_ACKTO GENMASK(15, 8)

#define MT_TX_RETRY_CFG 0x134c
#define MT_TX_LINK_CFG 0x1350
#define MT_TX_CFACK_EN BIT(12)
#define MT_VHT_HT_FBK_CFG0 0x1354
#define MT_VHT_HT_FBK_CFG1 0x1358
#define MT_LG_FBK_CFG0 0x135c
#define MT_LG_FBK_CFG1 0x1360

#define MT_PROT_CFG_RATE GENMASK(15, 0)
#define MT_PROT_CFG_CTRL GENMASK(17, 16)
#define MT_PROT_CFG_NAV GENMASK(19, 18)
#define MT_PROT_CFG_TXOP_ALLOW GENMASK(25, 20)
#define MT_PROT_CFG_RTS_THRESH BIT(26)

#define MT_CCK_PROT_CFG 0x1364
#define MT_OFDM_PROT_CFG 0x1368
#define MT_MM20_PROT_CFG 0x136c
#define MT_MM40_PROT_CFG 0x1370
#define MT_GF20_PROT_CFG 0x1374
#define MT_GF40_PROT_CFG 0x1378

#define MT_PROT_RATE GENMASK(15, 0)
#define MT_PROT_CTRL_RTS_CTS BIT(16)
#define MT_PROT_CTRL_CTS2SELF BIT(17)
#define MT_PROT_NAV_SHORT BIT(18)
#define MT_PROT_NAV_LONG BIT(19)
#define MT_PROT_TXOP_ALLOW_CCK BIT(20)
#define MT_PROT_TXOP_ALLOW_OFDM BIT(21)
#define MT_PROT_TXOP_ALLOW_MM20 BIT(22)
#define MT_PROT_TXOP_ALLOW_MM40 BIT(23)
#define MT_PROT_TXOP_ALLOW_GF20 BIT(24)
#define MT_PROT_TXOP_ALLOW_GF40 BIT(25)
#define MT_PROT_RTS_THR_EN BIT(26)
#define MT_PROT_RATE_CCK_11 0x0003
#define MT_PROT_RATE_OFDM_6 0x2000
#define MT_PROT_RATE_OFDM_24 0x2004
#define MT_PROT_RATE_DUP_OFDM_24 0x2084
#define MT_PROT_RATE_SGI_OFDM_24 0x2104
#define MT_PROT_TXOP_ALLOW_ALL GENMASK(25, 20)
#define MT_PROT_TXOP_ALLOW_BW20 (MT_PROT_TXOP_ALLOW_ALL & \
 ~MT_PROT_TXOP_ALLOW_MM40 & \
 ~MT_PROT_TXOP_ALLOW_GF40)

#define MT_EXP_ACK_TIME 0x1380

#define MT_TX_PWR_CFG_0_EXT 0x1390
#define MT_TX_PWR_CFG_1_EXT 0x1394

#define MT_TX_FBK_LIMIT 0x1398
#define MT_TX_FBK_LIMIT_MPDU_FBK GENMASK(7, 0)
#define MT_TX_FBK_LIMIT_AMPDU_FBK GENMASK(15, 8)
#define MT_TX_FBK_LIMIT_MPDU_UP_CLEAR BIT(16)
#define MT_TX_FBK_LIMIT_AMPDU_UP_CLEAR BIT(17)
#define MT_TX_FBK_LIMIT_RATE_LUT BIT(18)

#define MT_TX0_RF_GAIN_CORR 0x13a0
#define MT_TX1_RF_GAIN_CORR 0x13a4
#define MT_TX0_RF_GAIN_ATTEN 0x13a8

#define MT_TX_ALC_CFG_0 0x13b0
#define MT_TX_ALC_CFG_0_CH_INIT_0 GENMASK(5, 0)
#define MT_TX_ALC_CFG_0_CH_INIT_1 GENMASK(13, 8)
#define MT_TX_ALC_CFG_0_LIMIT_0 GENMASK(21, 16)
#define MT_TX_ALC_CFG_0_LIMIT_1 GENMASK(29, 24)

#define MT_TX_ALC_CFG_1 0x13b4
#define MT_TX_ALC_CFG_1_TEMP_COMP GENMASK(5, 0)

#define MT_TX_ALC_CFG_2 0x13a8
#define MT_TX_ALC_CFG_2_TEMP_COMP GENMASK(5, 0)

#define MT_TX_ALC_CFG_3 0x13ac
#define MT_TX_ALC_CFG_4 0x13c0
#define MT_TX_ALC_CFG_4_LOWGAIN_CH_EN BIT(31)

#define MT_TX_ALC_VGA3 0x13c8

#define MT_TX_PROT_CFG6 0x13e0
#define MT_TX_PROT_CFG7 0x13e4
#define MT_TX_PROT_CFG8 0x13e8

#define MT_PIFS_TX_CFG 0x13ec

#define MT_RX_FILTR_CFG 0x1400

#define MT_RX_FILTR_CFG_CRC_ERR BIT(0)
#define MT_RX_FILTR_CFG_PHY_ERR BIT(1)
#define MT_RX_FILTR_CFG_PROMISC BIT(2)
#define MT_RX_FILTR_CFG_OTHER_BSS BIT(3)
#define MT_RX_FILTR_CFG_VER_ERR BIT(4)
#define MT_RX_FILTR_CFG_MCAST BIT(5)
#define MT_RX_FILTR_CFG_BCAST BIT(6)
#define MT_RX_FILTR_CFG_DUP BIT(7)
#define MT_RX_FILTR_CFG_CFACK BIT(8)
#define MT_RX_FILTR_CFG_CFEND BIT(9)
#define MT_RX_FILTR_CFG_ACK BIT(10)
#define MT_RX_FILTR_CFG_CTS BIT(11)
#define MT_RX_FILTR_CFG_RTS BIT(12)
#define MT_RX_FILTR_CFG_PSPOLL BIT(13)
#define MT_RX_FILTR_CFG_BA BIT(14)
#define MT_RX_FILTR_CFG_BAR BIT(15)
#define MT_RX_FILTR_CFG_CTRL_RSV BIT(16)

#define MT_AUTO_RSP_CFG 0x1404
#define MT_AUTO_RSP_EN BIT(0)
#define MT_AUTO_RSP_PREAMB_SHORT BIT(4)
#define MT_LEGACY_BASIC_RATE 0x1408
#define MT_HT_BASIC_RATE 0x140c

#define MT_HT_CTRL_CFG 0x1410
#define MT_RX_PARSER_CFG 0x1418
#define MT_RX_PARSER_RX_SET_NAV_ALL BIT(0)

#define MT_EXT_CCA_CFG 0x141c
#define MT_EXT_CCA_CFG_CCA0 GENMASK(1, 0)
#define MT_EXT_CCA_CFG_CCA1 GENMASK(3, 2)
#define MT_EXT_CCA_CFG_CCA2 GENMASK(5, 4)
#define MT_EXT_CCA_CFG_CCA3 GENMASK(7, 6)
#define MT_EXT_CCA_CFG_CCA_MASK GENMASK(11, 8)
#define MT_EXT_CCA_CFG_ED_CCA_MASK GENMASK(15, 12)

#define MT_TX_SW_CFG3 0x1478

#define MT_PN_PAD_MODE 0x150c

#define MT_TXOP_HLDR_ET 0x1608
#define MT_TXOP_HLDR_TX40M_BLK_EN BIT(1)

#define MT_PROT_AUTO_TX_CFG 0x1648
#define MT_PROT_AUTO_TX_CFG_PROT_PADJ GENMASK(11, 8)
#define MT_PROT_AUTO_TX_CFG_AUTO_PADJ GENMASK(27, 24)

#define MT_RX_STAT_0 0x1700
#define MT_RX_STAT_0_CRC_ERRORS GENMASK(15, 0)
#define MT_RX_STAT_0_PHY_ERRORS GENMASK(31, 16)

#define MT_RX_STAT_1 0x1704
#define MT_RX_STAT_1_CCA_ERRORS GENMASK(15, 0)
#define MT_RX_STAT_1_PLCP_ERRORS GENMASK(31, 16)

#define MT_RX_STAT_2 0x1708
#define MT_RX_STAT_2_DUP_ERRORS GENMASK(15, 0)
#define MT_RX_STAT_2_OVERFLOW_ERRORS GENMASK(31, 16)

#define MT_TX_STA_0 0x170c
#define MT_TX_STA_1 0x1710
#define MT_TX_STA_2 0x1714

#define MT_TX_STAT_FIFO 0x1718
#define MT_TX_STAT_FIFO_VALID BIT(0)
#define MT_TX_STAT_FIFO_SUCCESS BIT(5)
#define MT_TX_STAT_FIFO_AGGR BIT(6)
#define MT_TX_STAT_FIFO_ACKREQ BIT(7)
#define MT_TX_STAT_FIFO_WCID GENMASK(15, 8)
#define MT_TX_STAT_FIFO_RATE GENMASK(31, 16)

#define MT_TX_AGG_STAT 0x171c

#define MT_TX_AGG_CNT_BASE0 0x1720
#define MT_MPDU_DENSITY_CNT 0x1740
#define MT_TX_AGG_CNT_BASE1 0x174c

#define MT_TX_AGG_CNT(_id) ((_id) < 8 ? \
    MT_TX_AGG_CNT_BASE0 + ((_id) << 2) : \
    MT_TX_AGG_CNT_BASE1 + (((_id) - 8) << 2))

#define MT_TX_STAT_FIFO_EXT 0x1798
#define MT_TX_STAT_FIFO_EXT_RETRY GENMASK(7, 0)
#define MT_TX_STAT_FIFO_EXT_PKTID GENMASK(15, 8)

#define MT_WCID_TX_RATE_BASE 0x1c00
#define MT_WCID_TX_RATE(_i) (MT_WCID_TX_RATE_BASE + ((_i) << 3))

#define MT_BBP_CORE_BASE 0x2000
#define MT_BBP_IBI_BASE 0x2100
#define MT_BBP_AGC_BASE 0x2300
#define MT_BBP_TXC_BASE 0x2400
#define MT_BBP_RXC_BASE 0x2500
#define MT_BBP_TXO_BASE 0x2600
#define MT_BBP_TXBE_BASE 0x2700
#define MT_BBP_RXFE_BASE 0x2800
#define MT_BBP_RXO_BASE 0x2900
#define MT_BBP_DFS_BASE 0x2a00
#define MT_BBP_TR_BASE 0x2b00
#define MT_BBP_CAL_BASE 0x2c00
#define MT_BBP_DSC_BASE 0x2e00
#define MT_BBP_PFMU_BASE 0x2f00

#define MT_BBP(_type, _n) (MT_BBP_##_type##_BASE + ((_n) << 2))

#define MT_BBP_CORE_R1_BW GENMASK(4, 3)

#define MT_BBP_AGC_R0_CTRL_CHAN GENMASK(9, 8)
#define MT_BBP_AGC_R0_BW GENMASK(14, 12)

// AGC, R4/R5
#define MT_BBP_AGC_LNA_HIGH_GAIN GENMASK(21, 16)
#define MT_BBP_AGC_LNA_MID_GAIN GENMASK(13, 8)
#define MT_BBP_AGC_LNA_LOW_GAIN GENMASK(5, 0)

// AGC, R6/R7
#define MT_BBP_AGC_LNA_ULOW_GAIN GENMASK(5, 0)

// AGC, R8/R9
#define MT_BBP_AGC_LNA_GAIN_MODE GENMASK(7, 6)
#define MT_BBP_AGC_GAIN GENMASK(14, 8)

#define MT_BBP_AGC20_RSSI0 GENMASK(7, 0)
#define MT_BBP_AGC20_RSSI1 GENMASK(15, 8)

#define MT_BBP_TXBE_R0_CTRL_CHAN GENMASK(1, 0)

#define MT_WCID_ADDR_BASE 0x1800
#define MT_WCID_ADDR(_n) (MT_WCID_ADDR_BASE + (_n) * 8)

#define MT_SRAM_BASE 0x4000

#define MT_WCID_KEY_BASE 0x8000
#define MT_WCID_KEY(_n) (MT_WCID_KEY_BASE + (_n) * 32)

#define MT_WCID_IV_BASE 0xa000
#define MT_WCID_IV(_n) (MT_WCID_IV_BASE + (_n) * 8)

#define MT_WCID_ATTR_BASE 0xa800
#define MT_WCID_ATTR(_n) (MT_WCID_ATTR_BASE + (_n) * 4)

#define MT_WCID_ATTR_PAIRWISE BIT(0)
#define MT_WCID_ATTR_PKEY_MODE GENMASK(3, 1)
#define MT_WCID_ATTR_BSS_IDX GENMASK(6, 4)
#define MT_WCID_ATTR_RXWI_UDF GENMASK(9, 7)
#define MT_WCID_ATTR_PKEY_MODE_EXT BIT(10)
#define MT_WCID_ATTR_BSS_IDX_EXT BIT(11)
#define MT_WCID_ATTR_WAPI_MCBC BIT(15)
#define MT_WCID_ATTR_WAPI_KEYID GENMASK(31, 24)

#define MT_SKEY_BASE_0 0xac00
#define MT_SKEY_BASE_1 0xb400
#define MT_SKEY_0(_bss, _idx) (MT_SKEY_BASE_0 + (4 * (_bss) + (_idx)) * 32)
#define MT_SKEY_1(_bss, _idx) (MT_SKEY_BASE_1 + (4 * ((_bss) & 7) + (_idx)) * 32)
#define MT_SKEY(_bss, _idx) (((_bss) & 8) ? MT_SKEY_1(_bss, _idx) : MT_SKEY_0(_bss, _idx))

#define MT_SKEY_MODE_BASE_0 0xb000
#define MT_SKEY_MODE_BASE_1 0xb3f0
#define MT_SKEY_MODE_0(_bss) (MT_SKEY_MODE_BASE_0 + (((_bss) / 2) << 2))
#define MT_SKEY_MODE_1(_bss) (MT_SKEY_MODE_BASE_1 + ((((_bss) & 7) / 2) << 2))
#define MT_SKEY_MODE(_bss) (((_bss) & 8) ? MT_SKEY_MODE_1(_bss) : MT_SKEY_MODE_0(_bss))
#define MT_SKEY_MODE_MASK GENMASK(3, 0)
#define MT_SKEY_MODE_SHIFT(_bss, _idx) (4 * ((_idx) + 4 * ((_bss) & 1)))

#define MT_BEACON_BASE 0xc000

#define MT_TEMP_SENSOR 0x1d000
#define MT_TEMP_SENSOR_VAL GENMASK(6, 0)

#define MT_EE_CHIP_ID 0x000
#define MT_EE_VERSION 0x002
#define MT_EE_MAC_ADDR 0x004
#define MT_EE_PCI_ID 0x00A
#define MT_EE_ANTENNA 0x022
#define MT_EE_CFG1_INIT 0x024
#define MT_EE_NIC_CONF_0 0x034
#define MT_EE_NIC_CONF_1 0x036
#define MT_EE_COUNTRY_REGION_5GHZ 0x038
#define MT_EE_COUNTRY_REGION_2GHZ 0x039
#define MT_EE_FREQ_OFFSET 0x03a
#define MT_EE_NIC_CONF_2 0x042
#define MT_EE_XTAL_TRIM_1 0x03a
#define MT_EE_XTAL_TRIM_2 0x09e
#define MT_EE_LNA_GAIN 0x044
#define MT_EE_RSSI_OFFSET_2G_0 0x046
#define MT_EE_RSSI_OFFSET_2G_1 0x048
#define MT_EE_LNA_GAIN_5GHZ_1 0x049
#define MT_EE_RSSI_OFFSET_5G_0 0x04a
#define MT_EE_RSSI_OFFSET_5G_1 0x04c
#define MT_EE_LNA_GAIN_5GHZ_2 0x04d
#define MT_EE_TX_POWER_DELTA_BW40 0x050
#define MT_EE_TX_POWER_DELTA_BW80 0x052
#define MT_EE_TX_POWER_EXT_PA_5G 0x054
#define MT_EE_TX_POWER_0_START_2G 0x056
#define MT_EE_TX_POWER_1_START_2G 0x05c
#define MT_EE_TX_POWER_0_START_5G 0x062
#define MT_EE_TSSI_SLOPE_2G 0x06e
#define MT_EE_TX_POWER_0_GRP3_TX_POWER_DELTA 0x074
#define MT_EE_TX_POWER_0_GRP4_TSSI_SLOPE 0x076
#define MT_EE_TX_POWER_1_START_5G 0x080
#define MT_EE_TX_POWER_CCK 0x0a0
#define MT_EE_TX_POWER_OFDM_2G_6M 0x0a2
#define MT_EE_TX_POWER_OFDM_2G_24M 0x0a4
#define MT_EE_TX_POWER_OFDM_5G_6M 0x0b2
#define MT_EE_TX_POWER_OFDM_5G_24M 0x0b4
#define MT_EE_TX_POWER_HT_MCS0 0x0a6
#define MT_EE_TX_POWER_HT_MCS4 0x0a8
#define MT_EE_TX_POWER_HT_MCS8 0x0aa
#define MT_EE_TX_POWER_HT_MCS12 0x0ac
#define MT_EE_TX_POWER_VHT_MCS0 0x0ba
#define MT_EE_TX_POWER_VHT_MCS4 0x0bc
#define MT_EE_TX_POWER_VHT_MCS8 0x0be
#define MT_EE_2G_TARGET_POWER 0x0d0
#define MT_EE_TEMP_OFFSET 0x0d1
#define MT_EE_5G_TARGET_POWER 0x0d2
#define MT_EE_TSSI_BOUND1 0x0d4
#define MT_EE_TSSI_BOUND2 0x0d6
#define MT_EE_TSSI_BOUND3 0x0d8
#define MT_EE_TSSI_BOUND4 0x0da
#define MT_EE_FREQ_OFFSET_COMPENSATION 0x0db
#define MT_EE_TSSI_BOUND5 0x0dc
#define MT_EE_TX_POWER_BYRATE_BASE 0x0de
#define MT_EE_TSSI_SLOPE_5G 0x0f0
#define MT_EE_RF_TEMP_COMP_SLOPE_5G 0x0f2
#define MT_EE_RF_TEMP_COMP_SLOPE_2G 0x0f4
#define MT_EE_RF_2G_TSSI_OFF_TXPOWER 0x0f6
#define MT_EE_RF_2G_RX_HIGH_GAIN 0x0f8
#define MT_EE_RF_5G_GRP0_1_RX_HIGH_GAIN 0x0fa
#define MT_EE_RF_5G_GRP2_3_RX_HIGH_GAIN 0x0fc
#define MT_EE_RF_5G_GRP4_5_RX_HIGH_GAIN 0x0fe
#define MT_EE_BT_RCAL_RESULT 0x138
#define MT_EE_BT_VCDL_CALIBRATION 0x13c
#define MT_EE_BT_PMUCFG 0x13e
#define MT_EE_USAGE_MAP_START 0x1e0
#define MT_EE_USAGE_MAP_END 0x1fc

#define MT_EE_TX_POWER_GROUP_SIZE_5G 5

/* The defines below belong to this project */

// Poll timeout
#define MT_TIMEOUT_POLL std::chrono::seconds(1)

// Power-on RF patch
#define MT_RF_PATCH 0x0130

// Firmware defines
// DLM offset differs from OpenWrt source
#define MT_FW_RESET_IVB 0x01
#define MT_MCU_ILM_OFFSET 0x80000
#define MT_MCU_DLM_OFFSET 0x100000 + 0x10800
#define MT_FW_CHUNK_SIZE 0x3800
#define MT_DMA_COMPLETE 0xc0000000
#define MT_FW_LOAD_IVB 0x12

// Register offset in memory
#define MT_REGISTER_OFFSET 0x410000

// Subgroups for channel power offsets
#define MT_CH_2G_LOW 0x01
#define MT_CH_2G_MID 0x02
#define MT_CH_2G_HIGH 0x03
#define MT_CH_5G_LOW 0x01
#define MT_CH_5G_HIGH 0x02

// Channel power limits (0 dB to 23.5 dB)
#define MT_CH_POWER_MIN 0x00
#define MT_CH_POWER_MAX 0x2f

Mt76::Mt76(
    std::unique_ptr<UsbDevice> usbDevice
) : usbDevice(std::move(usbDevice))
{ }

Mt76::~Mt76()
{
    if (!setLedMode(MT_LED_OFF))
    {
        Log::error("Failed to turn off LED");
    }

    if (!powerMode(RADIO_OFF))
    {
        Log::error("Failed to turn off radio");
    }
}

bool Mt76::init(std::string firmwarePath){
    if (!loadFirmware(firmwarePath))
    {
        Log::error("Failed to load firmware");
        return false;
    }

    // Select RX ring buffer 1
    // Turn radio on
    // Load BBP command register
    if (
            !selectFunction(Q_SELECT, 1) ||
            !powerMode(RADIO_ON) ||
            !loadCr(MT_RF_BBP_CR)
            ) {
        Log::error("Failed to init radio");
        return false;
    }

    if (!initRegisters())
    {
        Log::error("Failed to init registers");
        return false;
    }

    if (!sendFirmwareCommand(FW_MAC_ADDRESS_SET, macAddress))
    {
        Log::error("Failed to set MAC address");
        return false;
    }

    // Reset necessary for reliable WLAN associations
    controlWrite(MT_MAC_SYS_CTRL, 0);
    controlWrite(MT_RF_BYPASS_0, 0);
    controlWrite(MT_RF_SETTING_0, 0);

    if (
            !calibrate(MCU_CAL_TEMP_SENSOR, 0) ||
            !calibrate(MCU_CAL_RXDCOC, 1) ||
            !calibrate(MCU_CAL_RC, 0)
            ) {
        Log::error("Failed to calibrate chip");
        return false;
    }

    controlWrite(
            MT_MAC_SYS_CTRL,
            MT_MAC_SYS_CTRL_ENABLE_TX | MT_MAC_SYS_CTRL_ENABLE_RX
    );

    if (!initChannels())
    {
        Log::error("Failed to init channels");
        return false;
    }

    if (!writeBeacon(false))
    {
        Log::error("Failed to write beacon");
        return false;
    }
    return true;
}

uint8_t Mt76::associateClient(Bytes address)
{
    // Find first available WCID
    uint16_t freeIds = static_cast<uint16_t>(~connectedClients);
    uint8_t wcid = __builtin_ffs(freeIds);

    if (wcid == 0)
    {
        Log::error("All WCIDs are taken");

        return 0;
    }

    connectedClients |= BIT(wcid - 1);

    TxWi txWi = {};

    // OFDM transmission method
    // Wait for acknowledgement
    // Ignore wireless client identifier (WCID)
    txWi.phyType = MT_PHY_TYPE_OFDM;
    txWi.ack = true;
    txWi.wcid = 0xff;
    txWi.mpduByteCount = sizeof(WlanFrame) + sizeof(AssociationResponseFrame);

    WlanFrame wlanFrame = {};

    wlanFrame.frameControl.type = MT_WLAN_MANAGEMENT;
    wlanFrame.frameControl.subtype = MT_WLAN_ASSOCIATION_RESP;

    address.copy(wlanFrame.destination);
    macAddress.copy(wlanFrame.source);
    macAddress.copy(wlanFrame.bssId);

    AssociationResponseFrame associationFrame = {};

    // Original status code
    // Original association ID
    associationFrame.statusCode = 0x0110;
    associationFrame.associationId = 0x0f00;

    Bytes out;

    out.append(txWi);
    out.append(wlanFrame);
    out.append(associationFrame);

    const Bytes wcidData = {
        static_cast<uint8_t>(wcid - 1), 0x00, 0x00, 0x00,
        0x40, 0x1f, 0x00, 0x00
    };

    // WCID 0 is reserved for beacon frames
    if (!burstWrite(MT_WCID_ADDR(wcid), address))
    {
        Log::error("Failed to write WCID");

        return 0;
    }

    if (!sendFirmwareCommand(FW_CLIENT_ADD, wcidData))
    {
        Log::error("Failed to add client");

        return 0;
    }

    if (!sendWlanPacket(out))
    {
        Log::error("Failed to send association packet");

        return 0;
    }

    if (!setLedMode(MT_LED_ON))
    {
        Log::error("Failed to set LED mode");

        return 0;
    }

    return wcid;
}

bool Mt76::removeClient(uint8_t wcid)
{
    const Bytes emptyAddress = { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
    const Bytes wcidData = {
        static_cast<uint8_t>(wcid - 1), 0x00, 0x00, 0x00
    };

    // Remove WCID from connected clients
    connectedClients &= ~BIT(wcid - 1);

    if (!sendFirmwareCommand(FW_CLIENT_REMOVE, wcidData))
    {
        Log::error("Failed to remove client");

        return false;
    }

    if (!burstWrite(MT_WCID_ADDR(wcid), emptyAddress))
    {
        Log::error("Failed to write WCID");

        return false;
    }

    if (connectedClients == 0 && !setLedMode(MT_LED_OFF))
    {
        Log::error("Failed to set LED mode");

        return false;
    }

    return true;
}

bool Mt76::pairClient(Bytes address)
{
    const Bytes data = {
        0x70, 0x02, 0x00, 0x45,
        0x55, 0x01, 0x0f, 0x8f,
        0xff, 0x87, 0x1f
    };

    TxWi txWi = {};

    // OFDM transmission method
    // Wait for acknowledgement
    // Ignore wireless client index (WCID)
    txWi.phyType = MT_PHY_TYPE_OFDM;
    txWi.ack = true;
    txWi.wcid = 0xff;
    txWi.mpduByteCount = sizeof(WlanFrame) + data.size();

    WlanFrame wlanFrame = {};

    wlanFrame.frameControl.type = MT_WLAN_MANAGEMENT;
    wlanFrame.frameControl.subtype = MT_WLAN_RESERVED;

    address.copy(wlanFrame.destination);
    macAddress.copy(wlanFrame.source);
    macAddress.copy(wlanFrame.bssId);

    Bytes out;

    out.append(txWi);
    out.append(wlanFrame);
    out.append(data);

    if (!sendWlanPacket(out))
    {
        Log::error("Failed to send pairing packet");

        return false;
    }

    return true;
}

bool Mt76::sendClientPacket(
    uint8_t wcid,
    Bytes address,
    const Bytes &packet
) {
    // Skip unconnected WCIDs
    if ((connectedClients & BIT(wcid - 1)) == 0)
    {
        return true;
    }

    TxWi txWi = {};

    // OFDM transmission method
    // Wait for acknowledgement
    txWi.phyType = MT_PHY_TYPE_OFDM;
    txWi.ack = true;
    txWi.mpduByteCount = sizeof(WlanFrame) + sizeof(QosFrame) + packet.size();

    WlanFrame wlanFrame = {};

    // Frame is sent from AP (DS)
    // Duration is the time required to transmit (μs)
    wlanFrame.frameControl.type = MT_WLAN_DATA;
    wlanFrame.frameControl.subtype = MT_WLAN_QOS_DATA;
    wlanFrame.frameControl.fromDs = true;
    wlanFrame.duration = 144;

    address.copy(wlanFrame.destination);
    macAddress.copy(wlanFrame.source);
    macAddress.copy(wlanFrame.bssId);

    QosFrame qosFrame = {};

    // Frames and data must be 32-bit aligned
    uint32_t length = sizeof(txWi) + sizeof(wlanFrame) + sizeof(qosFrame);
    uint32_t wcidData = __builtin_bswap32(wcid - 1);
    uint8_t framePadding = Bytes::padding<uint32_t>(length);
    uint8_t dataPadding = Bytes::padding<uint32_t>(packet.size());

    Bytes out;

    out.append(wcidData);
    out.pad(sizeof(uint32_t));
    out.append(txWi);
    out.append(wlanFrame);
    out.append(qosFrame);
    out.pad(framePadding);
    out.append(packet);
    out.pad(dataPadding);

    if (!sendCommand(CMD_PACKET_TX, out))
    {
        Log::error("Failed to send controller packet");

        return false;
    }

    return true;
}

bool Mt76::setPairingStatus(bool enable)
{
    // Set the pairing status for the beacon
    if (!writeBeacon(enable))
    {
        Log::error("Failed to write beacon");

        return false;
    }

    if (!setLedMode(enable ? MT_LED_BLINK : MT_LED_ON))
    {
        Log::error("Failed to set LED mode");

        return false;
    }

    Log::info(enable ? "Pairing enabled" : "Pairing disabled");

    return true;
}

bool Mt76::sendWlanPacket(const Bytes &data)
{
    // Values must be 32-bit aligned
    // 32 zero-bits mark the end
    uint32_t length = data.size();
    uint8_t padding = Bytes::padding<uint32_t>(length);

    TxInfoPacket info = {};

    // 802.11 WLAN packet
    // Wireless information valid (WIV)
    // Enhanced distributed channel access (EDCA)
    info.port = WLAN_PORT;
    info.infoType = NORMAL_PACKET;
    info.is80211 = true;
    info.wiv = true;
    info.qsel = MT_QSEL_EDCA;
    info.length = length + padding;

    Bytes out;

    out.append(info);
    out.append(data);
    out.pad(padding);
    out.pad(sizeof(uint32_t));

    if (!usbDevice->bulkWrite(MT_EP_WRITE, out))
    {
        Log::error("Failed to write WLAN packet");

        return false;
    }

    return true;
}

bool Mt76::initRegisters()
{
    controlWrite(
        MT_MAC_SYS_CTRL,
        MT_MAC_SYS_CTRL_RESET_CSR | MT_MAC_SYS_CTRL_RESET_BBP
    );
    controlWrite(MT_USB_DMA_CFG, 0);
    controlWrite(MT_MAC_SYS_CTRL, 0);
    controlWrite(MT_PWR_PIN_CFG, 0);
    controlWrite(MT_LDO_CTRL_1, 0x6b006464);
    controlWrite(MT_WPDMA_GLO_CFG, 0x70);
    controlWrite(MT_WMM_AIFSN, 0x2273);
    controlWrite(MT_WMM_CWMIN, 0x2344);
    controlWrite(MT_WMM_CWMAX, 0x34aa);
    controlWrite(MT_FCE_DMA_ADDR, 0x041200);
    controlWrite(MT_TSO_CTRL, 0);
    controlWrite(MT_PBF_SYS_CTRL, 0x080c00);
    controlWrite(MT_PBF_TX_MAX_PCNT, 0x1fbf1f1f);
    controlWrite(MT_FCE_PSE_CTRL, 0x01);
    controlWrite(
        MT_MAC_SYS_CTRL,
        MT_MAC_SYS_CTRL_ENABLE_TX | MT_MAC_SYS_CTRL_ENABLE_RX
    );
    controlWrite(MT_AUTO_RSP_CFG, 0x13);
    controlWrite(MT_MAX_LEN_CFG, 0x3e3fff);
    controlWrite(MT_AMPDU_MAX_LEN_20M1S, 0xfffc9855);
    controlWrite(MT_AMPDU_MAX_LEN_20M2S, 0xff);
    controlWrite(MT_BKOFF_SLOT_CFG, 0x0109);
    controlWrite(MT_PWR_PIN_CFG, 0);
    controlWrite(MT_EDCA_CFG_AC(0), 0x064320);
    controlWrite(MT_EDCA_CFG_AC(1), 0x0a4700);
    controlWrite(MT_EDCA_CFG_AC(2), 0x043238);
    controlWrite(MT_EDCA_CFG_AC(3), 0x03212f);
    controlWrite(MT_TX_PIN_CFG, 0x150f0f);
    controlWrite(MT_TX_SW_CFG0, 0x101001);
    controlWrite(MT_TX_SW_CFG1, 0x010000);
    controlWrite(MT_TXOP_CTRL_CFG, 0x10583f);
    controlWrite(MT_TX_TIMEOUT_CFG, 0x0a0f90);
    controlWrite(MT_TX_RETRY_CFG, 0x47d01f0f);
    controlWrite(MT_CCK_PROT_CFG, 0x03f40003);
    controlWrite(MT_OFDM_PROT_CFG, 0x03f40003);
    controlWrite(MT_MM20_PROT_CFG, 0x01742004);
    controlWrite(MT_GF20_PROT_CFG, 0x01742004);
    controlWrite(MT_GF40_PROT_CFG, 0x03f42084);
    controlWrite(MT_EXP_ACK_TIME, 0x2c00dc);
    controlWrite(MT_TX_ALC_CFG_2, 0x22160a00);
    controlWrite(MT_TX_ALC_CFG_3, 0x22160a76);
    controlWrite(MT_TX_ALC_CFG_0, 0x3f3f1818);
    controlWrite(MT_TX_ALC_CFG_4, 0x0606);
    controlWrite(MT_PIFS_TX_CFG, 0x060fff);
    controlWrite(MT_RX_FILTR_CFG, 0x017f17);
    controlWrite(MT_LEGACY_BASIC_RATE, 0x017f);
    controlWrite(MT_HT_BASIC_RATE, 0x8003);
    controlWrite(MT_PN_PAD_MODE, 0x02);
    controlWrite(MT_TXOP_HLDR_ET, 0x02);
    controlWrite(MT_TX_PROT_CFG6, 0xe3f42004);
    controlWrite(MT_TX_PROT_CFG7, 0xe3f42084);
    controlWrite(MT_TX_PROT_CFG8, 0xe3f42104);
    controlWrite(MT_DACCLK_EN_DLY_CFG, 0);
    controlWrite(MT_RF_PA_MODE_ADJ0, 0xee000000);
    controlWrite(MT_RF_PA_MODE_ADJ1, 0xee000000);
    controlWrite(MT_TX0_RF_GAIN_CORR, 0x0f3c3c3c);
    controlWrite(MT_TX1_RF_GAIN_CORR, 0x0f3c3c3c);
    controlWrite(MT_PBF_CFG, 0x1efebcf5);
    controlWrite(MT_PAUSE_ENABLE_CONTROL1, 0x0a);
    controlWrite(MT_RF_BYPASS_0, 0x7f000000);
    controlWrite(MT_RF_SETTING_0, 0x1a800000);
    controlWrite(MT_XIFS_TIME_CFG, 0x33a40e0a);
    controlWrite(MT_FCE_L2_STUFF, 0x03ff0223);
    controlWrite(MT_TX_RTS_CFG, 0);
    controlWrite(MT_BEACON_TIME_CFG, 0x0640);
    controlWrite(MT_EXT_CCA_CFG, 0xf0e4);
    controlWrite(MT_CH_TIME_CFG, 0x015f);

    // Calibrate internal crystal oscillator
    if (!calibrateCrystal())
    {
        Log::error("Failed to calibrate crystal");

        return false;
    }

    // Configure automatic gain control (AGC)
    controlWrite(MT_BBP(AGC, 8), 0x18365efa);
    controlWrite(MT_BBP(AGC, 9), 0x18365efa);

    macAddress = efuseRead(MT_EE_MAC_ADDR, 6);

    if (macAddress.size() < 6)
    {
        Log::error("Failed to read MAC address");

        return false;
    }

    // Some dongles' addresses start with 6c:5d:3a
    // Controllers only connect to 62:45:bx:xx:xx:xx
    if (macAddress[0] != 0x62)
    {
        Log::debug("Invalid MAC address, correcting...");

        macAddress[0] = 0x62;
        macAddress[1] = 0x45;
        macAddress[2] = 0xbd;
    }

    if (!burstWrite(MT_MAC_ADDR_DW0, macAddress))
    {
        Log::error("Failed to write MAC address");

        return false;
    }

    if (!burstWrite(MT_MAC_BSSID_DW0, macAddress))
    {
        Log::error("Failed to write BSSID");

        return false;
    }

    uint16_t asicVersion = controlRead(MT_ASIC_VERSION) >> 16;
    uint16_t macVersion = controlRead(MT_MAC_CSR0) >> 16;
    Bytes chipId = efuseRead(MT_EE_CHIP_ID, sizeof(uint32_t));

    if (chipId.size() < sizeof(uint32_t))
    {
        Log::error("Failed to read chip id");

        return false;
    }

    uint16_t id = (chipId[1] << 8) | chipId[2];

    Log::debug("ASIC version: %x", asicVersion);
    Log::debug("MAC version: %x", macVersion);
    Log::debug("Chip id: %x", id);
    Log::info("Wireless address: %s", Log::formatBytes(macAddress).c_str());

    return true;
}

bool Mt76::calibrateCrystal()
{
    Bytes trim = efuseRead(MT_EE_XTAL_TRIM_2, sizeof(uint32_t));

    if (trim.size() < sizeof(uint32_t))
    {
        Log::error("Failed to read second trim value");

        return false;
    }

    uint16_t value = (trim[3] << 8) | trim[2];
    int8_t offset = value & 0x7f;

    if ((value & 0xff) == 0xff)
    {
        offset = 0;
    }

    else if (value & 0x80)
    {
        offset = -offset;
    }

    value >>= 8;

    if (value == 0x00 || value == 0xff)
    {
        trim = efuseRead(MT_EE_XTAL_TRIM_1, sizeof(uint32_t));

        if (trim.size() < sizeof(uint32_t))
        {
            Log::error("Failed to read first trim value");

            return false;
        }

        value = (trim[3] << 8) | trim[2];
        value &= 0xff;

        if (value == 0x00 || value == 0xff)
        {
            value = 0x14;
        }
    }

    value = (value & 0x7f) + offset;

    uint32_t ctrl = controlRead(MT_XO_CTRL5) & ~MT_XO_CTRL5_C2_VAL;

    controlWrite(MT_XO_CTRL5, ctrl | (value << 8), MT_VEND_WRITE_CFG);
    controlWrite(MT_XO_CTRL6, MT_XO_CTRL6_C2_CTRL, MT_VEND_WRITE_CFG);
    controlWrite(MT_CMB_CTRL, 0x0091a7ff);

    return true;
}

bool Mt76::initChannels()
{
    // Configure each individual channel
    // Power for channels 0x24 - 0x30 gets increased by the original driver
    // It sometimes even exceeds the absolute maximum of 0x2f
    configureChannel(0x01, MT_CH_BW_20, true);
    configureChannel(0x06, MT_CH_BW_20, true);
    configureChannel(0x0b, MT_CH_BW_20, true);
    configureChannel(0x24, MT_CH_BW_40, true);
    configureChannel(0x28, MT_CH_BW_40, false);
    configureChannel(0x2c, MT_CH_BW_40, true);
    configureChannel(0x30, MT_CH_BW_40, false);
    configureChannel(0x95, MT_CH_BW_80, true);
    configureChannel(0x99, MT_CH_BW_80, false);
    configureChannel(0x9d, MT_CH_BW_80, true);
    configureChannel(0xa1, MT_CH_BW_80, false);
    configureChannel(0xa5, MT_CH_BW_80, false);

    // List of wireless channel candidates
    const Bytes candidates = {
        0x01, 0xa5,
        0x0b, 0x01,
        0x06, 0x0b,
        0x24, 0x28,
        0x2c, 0x30,
        0x95, 0x99,
        0x9d, 0xa1
    };
    Bytes values;

    // Map channels to 32-bit values
    for (uint32_t channel : candidates)
    {
        values.append(channel);
    }

    if (!sendFirmwareCommand(FW_CHANNEL_CANDIDATES_SET, values))
    {
        Log::error("Failed to set channel candidates");

        return false;
    }

    return true;
}
extern const uint8_t FW_ACC_00U[];
extern const size_t FW_ACC_00U_SIZE;

bool Mt76::loadFirmware(std::string firmwarePath)
{
    Bytes firmware(FW_ACC_00U, FW_ACC_00U+FW_ACC_00U_SIZE);

    if (controlRead(MT_FCE_DMA_ADDR, MT_VEND_READ_CFG))
    {
        Log::debug("Firmware already loaded, resetting...");

        uint32_t patch = controlRead(MT_RF_PATCH, MT_VEND_READ_CFG);

        patch &= ~BIT(19);

        // Mandatory for already initialized radios
        controlWrite(MT_RF_PATCH, patch, MT_VEND_WRITE_CFG);
        controlWrite(MT_FW_RESET_IVB, 0, MT_VEND_DEV_MODE);

        // Wait for firmware to reset
        bool successful = pollTimeout([this] {
            return controlRead(MT_FCE_DMA_ADDR, MT_VEND_READ_CFG) != 0x80000000;
        });

        if (!successful)
        {
            Log::error("Firmware reset timed out");

            return false;
        }
    }

    DmaConfig config = {};

    config.props.rxBulkEnabled = true;
    config.props.txBulkEnabled = true;

    // Configure direct memory access (DMA)
    // Enable FCE and packet DMA
    controlWrite(MT_USB_U3DMA_CFG, config.value, MT_VEND_WRITE_CFG);
    controlWrite(MT_FCE_PSE_CTRL, 0x01);
    controlWrite(MT_TX_CPU_FROM_FCE_BASE_PTR, 0x400230);
    controlWrite(MT_TX_CPU_FROM_FCE_MAX_COUNT, 0x01);
    controlWrite(MT_TX_CPU_FROM_FCE_CPU_DESC_IDX, 0x01);
    controlWrite(MT_FCE_PDMA_GLOBAL_CONF, 0x44);
    controlWrite(MT_FCE_SKIP_FS, 0x03);

    const FwHeader *header = firmware.toStruct<FwHeader>();
    Bytes::Iterator ilmStart = firmware.begin() + sizeof(FwHeader);
    Bytes::Iterator dlmStart = ilmStart + header->ilmLength;
    Bytes::Iterator dlmEnd = dlmStart + header->dlmLength;

    // Upload instruction local memory (ILM)
    if (!loadFirmwarePart(MT_MCU_ILM_OFFSET, ilmStart, dlmStart))
    {
        Log::error("Failed to write ILM");

        return false;
    }

    // Upload data local memory (DLM)
    if (!loadFirmwarePart(MT_MCU_DLM_OFFSET, dlmStart, dlmEnd))
    {
        Log::error("Failed to write DLM");

        return false;
    }

    // Load initial vector block (IVB)
    controlWrite(MT_FCE_DMA_ADDR, 0, MT_VEND_WRITE_CFG);
    controlWrite(MT_FW_LOAD_IVB, 0, MT_VEND_DEV_MODE);

    // Wait for firmware to start
    bool successful = pollTimeout([this] {
        return controlRead(MT_FCE_DMA_ADDR, MT_VEND_READ_CFG) != 0x01;
    });

    if (!successful)
    {
        Log::debug("Firmware loading timed out");

        return false;
    }

    Log::debug("Firmware loaded");

    return true;
}

bool Mt76::loadFirmwarePart(
    uint32_t offset,
    Bytes::Iterator start,
    Bytes::Iterator end
) {
    // Send firmware in chunks
    for (Bytes::Iterator chunk = start; chunk < end; chunk += MT_FW_CHUNK_SIZE)
    {
        uint32_t address = offset + chunk - start;
        uint32_t remaining = end - chunk;
        uint16_t length = remaining > MT_FW_CHUNK_SIZE
            ? MT_FW_CHUNK_SIZE
            : remaining;

        TxInfoCommand info = {};

        info.port = CPU_TX_PORT;
        info.infoType = NORMAL_PACKET;
        info.length = length;

        Bytes out;

        out.append(info);
        out.append(chunk, chunk + length);
        out.pad(sizeof(uint32_t));

        controlWrite(MT_FCE_DMA_ADDR, address, MT_VEND_WRITE_CFG);
        controlWrite(MT_FCE_DMA_LEN, length << 16, MT_VEND_WRITE_CFG);

        if (!usbDevice->bulkWrite(MT_EP_WRITE, out))
        {
            Log::error("Failed to write firmware chunk");

            return false;
        }

        uint32_t complete = (length << 16) | MT_DMA_COMPLETE;

        bool successful = pollTimeout([this, complete] {
            return controlRead(MT_FCE_DMA_LEN, MT_VEND_READ_CFG) != complete;
        });

        if (!successful)
        {
            Log::error("Firmware part loading timed out");

            return false;
        }
    }

    return true;
}

bool Mt76::writeBeacon(bool pairing)
{
    const Bytes broadcastAddress = { 0xff, 0xff, 0xff, 0xff, 0xff, 0xff };

    // Contains an information element (ID: 0xdd, Length: 0x10)
    // Probably includes the selected channel pair
    const Bytes data = {
        0xdd, 0x10, 0x00, 0x50,
        0xf2, 0x11, 0x01, 0x10,
        pairing, 0xa5, 0x30, 0x99,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00
    };

    TxWi txWi = {};

    // OFDM transmission method
    // Generate beacon timestamp
    // Use hardware sequence control
    txWi.phyType = MT_PHY_TYPE_OFDM;
    txWi.timestamp = true;
    txWi.nseq = true;
    txWi.mpduByteCount = sizeof(WlanFrame) + sizeof(BeaconFrame) + data.size();

    WlanFrame wlanFrame = {};

    wlanFrame.frameControl.type = MT_WLAN_MANAGEMENT;
    wlanFrame.frameControl.subtype = MT_WLAN_BEACON;

    broadcastAddress.copy(wlanFrame.destination);
    macAddress.copy(wlanFrame.source);
    macAddress.copy(wlanFrame.bssId);

    BeaconFrame beaconFrame = {};

    // Default beacon interval (100 ms)
    // Original capability info
    // Wildcard SSID
    beaconFrame.interval = 0x64;
    beaconFrame.capabilityInfo = 0xc631;

    Bytes out;

    out.append(txWi);
    out.append(wlanFrame);
    out.append(beaconFrame);
    out.append(data);

    BeaconTimeConfig config = {};

    // Enable timing synchronization function (TSF) timer
    // Enable target beacon transmission time (TBTT) timer
    // Set TSF timer to AP mode
    // Activate beacon transmission
    config.value = controlRead(MT_BEACON_TIME_CFG);
    config.props.tsfTimerEnabled = true;
    config.props.tbttTimerEnabled = true;
    config.props.tsfSyncMode = 3;
    config.props.transmitBeacon = true;

    if (!burstWrite(MT_BEACON_BASE, out))
    {
        Log::error("Failed to write beacon");

        return false;
    }

    controlWrite(MT_BEACON_TIME_CFG, config.value);

    if (!calibrate(MCU_CAL_RXDCOC, 0))
    {
        Log::error("Failed to calibrate beacon");

        return false;
    }

    return true;
}

bool Mt76::selectFunction(McuFunction function, uint32_t value)
{
    Bytes out;

    out.append(static_cast<uint32_t>(function));
    out.append(value);

    if (!sendCommand(CMD_FUN_SET_OP, out))
    {
        Log::error("Failed to select function");

        return false;
    }

    return true;
}

bool Mt76::powerMode(McuPowerMode mode)
{
    Bytes out;

    out.append(static_cast<uint32_t>(mode));

    if (!sendCommand(CMD_POWER_SAVING_OP, out))
    {
        Log::error("Failed to set power mode");

        return false;
    }

    return true;
}

bool Mt76::loadCr(McuCrMode mode)
{
    Bytes out;

    out.append(static_cast<uint32_t>(mode));

    if (!sendCommand(CMD_LOAD_CR, out))
    {
        Log::error("Failed to load CR");

        return false;
    }

    return true;
}

bool Mt76::burstWrite(uint32_t index, const Bytes &values)
{
    index += MT_REGISTER_OFFSET;

    Bytes out;

    out.append(index);
    out.append(values);

    if (!sendCommand(CMD_BURST_WRITE, out))
    {
        Log::error("Failed to burst write register");

        return false;
    }

    return true;
}

bool Mt76::calibrate(McuCalibration calibration, uint32_t value)
{
    Bytes out;

    out.append(static_cast<uint32_t>(calibration));
    out.append(value);

    if (!sendCommand(CMD_CALIBRATION_OP, out))
    {
        Log::error("Failed to calibrate");

        return false;
    }

    return true;
}

bool Mt76::configureChannel(
    uint8_t channel,
    McuChannelBandwidth bandwidth,
    bool scan
) {
    ChannelConfigData config = {};

    // Select TX and RX stream 1
    // Set transmit power
    // Set channel bandwidth
    // Enable or disable scanning (purpose unknown)
    config.channel = channel;
    config.txRxSetting = 0x0101;
    config.bandwidth = bandwidth;
    config.txPower = getChannelPower(channel);
    config.scan = scan;

    Bytes out;

    out.append(config);

    if (!sendCommand(CMD_SWITCH_CHANNEL_OP, out))
    {
        Log::error("Failed to switch channel");

        return false;
    }

    Log::debug("Channel %d, power: %d", channel, config.txPower);

    return true;
}

uint8_t Mt76::getChannelPower(uint8_t channel)
{
    // Channel group points to the entry in the power table
    // Channel subgroup points to the power offset value
    bool is24Ghz = channel <= 14;
    uint8_t powerTableIndex = is24Ghz ?
        MT_EE_TX_POWER_0_START_2G :
        MT_EE_TX_POWER_0_START_5G;
    uint8_t group = getChannelGroup(channel);
    uint8_t subgroup = getChannelSubgroup(channel);

    if (!is24Ghz)
    {
        powerTableIndex += group * MT_EE_TX_POWER_GROUP_SIZE_5G;
    }

    // Each channel group has its own power table
    Bytes entry = efuseRead(powerTableIndex, 8);

    if (entry.size() < 8)
    {
        Log::error("Failed to read power table entry");

        return MT_CH_POWER_MIN;
    }

    uint8_t index = is24Ghz ? 4 : 5;
    uint8_t powerTarget = entry[index];
    uint8_t powerOffset = entry[index + subgroup];

    // Enable (1) or disable (0) offset
    if ((powerOffset & BIT(7)) == 0)
    {
        return powerTarget;
    }

    // Decrease (0) or increase (1) power
    bool sign = powerOffset & BIT(6);

    // Power offset (in 0.5 dB steps)
    int8_t offset = powerOffset & 0x3f;
    int8_t power = sign ?
        powerTarget + offset :
        powerTarget - offset;

    if (power < MT_CH_POWER_MIN)
    {
        return MT_CH_POWER_MIN;
    }

    if (power > MT_CH_POWER_MAX)
    {
        return MT_CH_POWER_MAX;
    }

    return power;
}

uint8_t Mt76::getChannelGroup(uint8_t channel)
{
    if (channel >= 184 && channel <= 196)
    {
        return MT_CH_5G_JAPAN;
    }

    if (channel <= 48)
    {
        return MT_CH_5G_UNII_1;
    }

    if (channel <= 64)
    {
        return MT_CH_5G_UNII_2;
    }

    if (channel <= 114)
    {
        return MT_CH_5G_UNII_2E_1;
    }

    if (channel <= 144)
    {
        return MT_CH_5G_UNII_2E_2;
    }

    return MT_CH_5G_UNII_3;
}

uint8_t Mt76::getChannelSubgroup(uint8_t channel)
{
    if (channel >= 192)
    {
        return MT_CH_5G_HIGH;
    }

    else if (channel >= 184)
    {
        return MT_CH_5G_LOW;
    }

    else if (channel < 6)
    {
        return MT_CH_2G_LOW;
    }

    else if (channel < 11)
    {
        return MT_CH_2G_MID;
    }

    else if (channel < 15)
    {
        return MT_CH_2G_HIGH;
    }

    else if (channel < 44)
    {
        return MT_CH_5G_LOW;
    }

    else if (channel < 52)
    {
        return MT_CH_5G_HIGH;
    }

    else if (channel < 58)
    {
        return MT_CH_5G_LOW;
    }

    else if (channel < 98)
    {
        return MT_CH_5G_HIGH;
    }

    else if (channel < 106)
    {
        return MT_CH_5G_LOW;
    }

    else if (channel < 116)
    {
        return MT_CH_5G_HIGH;
    }

    else if (channel < 130)
    {
        return MT_CH_5G_LOW;
    }

    else if (channel < 149)
    {
        return MT_CH_5G_HIGH;
    }

    else if (channel < 157)
    {
        return MT_CH_5G_LOW;
    }

    return MT_CH_5G_HIGH;
}

bool Mt76::sendFirmwareCommand(McuFwCommand command, const Bytes &data)
{
    Bytes out;

    out.append(static_cast<uint32_t>(command));
    out.append(data);

    if (!sendCommand(CMD_INTERNAL_FW_OP, out))
    {
        Log::error("Failed to send firmware command");

        return false;
    }

    return true;
}

bool Mt76::setLedMode(uint32_t index)
{
    Bytes out;

    out.append(index);

    if (!sendCommand(CMD_LED_MODE_OP, out))
    {
        Log::error("Failed to set LED mode");

        return false;
    }

    return true;
}

bool Mt76::sendCommand(McuCommand command, const Bytes &data)
{
    // Values must be 32-bit aligned
    // 32 zero-bits mark the end
    uint32_t length = data.size();
    uint8_t padding = Bytes::padding<uint32_t>(length);

    // We ignore responses, sequence number is always zero
    TxInfoCommand info = {};

    info.port = CPU_TX_PORT;
    info.infoType = CMD_PACKET;
    info.command = command;
    info.length = length + padding;

    Bytes out;

    out.append(info);
    out.append(data);
    out.pad(padding);
    out.pad(sizeof(uint32_t));

    if (!usbDevice->bulkWrite(MT_EP_WRITE, out))
    {
        Log::error("Failed to write command");

        return false;
    }

    return true;
}

Bytes Mt76::efuseRead(uint8_t address, uint8_t length)
{
    EfuseControl control = {};

    // Read data in blocks of 4 * 32 bits
    // Kick-off read
    control.value = controlRead(MT_EFUSE_CTRL);
    control.props.mode = MT_EE_READ;
    control.props.addressIn = address & ~0x0f;
    control.props.kick = true;

    controlWrite(MT_EFUSE_CTRL, control.value);

    Bytes data;

    bool successful = pollTimeout([this] {
        return controlRead(MT_EFUSE_CTRL) & MT_EFUSE_CTRL_KICK;
    });

    if (!successful)
    {
        Log::error("Read from EFUSE timed out");

        return data;
    }

    for (uint8_t i = 0; i < length; i += sizeof(uint32_t))
    {
        // Block data offset (multiple of 32 bits)
        uint8_t offset = (address & 0x0c) + i;
        uint32_t value = controlRead(MT_EFUSE_DATA_BASE + offset);
        uint8_t remaining = length - i;
        uint8_t size = remaining < sizeof(uint32_t)
            ? remaining
            : sizeof(uint32_t);

        data.append(value, size);
    }

    return data;
}

bool Mt76::pollTimeout(std::function<bool()> condition)
{
    using Time = std::chrono::steady_clock::time_point;

    Time start = std::chrono::steady_clock::now();

    while (condition())
    {
        Time now = std::chrono::steady_clock::now();

        if (now - start > MT_TIMEOUT_POLL)
        {
            return false;
        }
    }

    return true;
}

uint32_t Mt76::controlRead(uint16_t address, VendorRequest request)
{
    uint32_t response = 0;
    UsbDevice::ControlPacket packet = {};

    packet.request = request;
    packet.index = address;
    packet.data = reinterpret_cast<uint8_t*>(&response);
    packet.length = sizeof(response);

    usbDevice->controlTransfer(packet, false);

    return response;
}

void Mt76::controlWrite(
    uint16_t address,
    uint32_t value,
    VendorRequest request
) {
    UsbDevice::ControlPacket packet = {};

    packet.request = request;

    if (request == MT_VEND_DEV_MODE)
    {
        packet.value = address;
    }

    else
    {
        packet.index = address;
        packet.data = reinterpret_cast<uint8_t*>(&value);
        packet.length = sizeof(value);
    }

    usbDevice->controlTransfer(packet, true);
}

Mt76Exception::Mt76Exception(
    std::string message
) : std::runtime_error(message) {}
