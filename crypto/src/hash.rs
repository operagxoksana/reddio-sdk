use std::ffi::CStr;

use elliptic_curve::bigint::{Encoding, Wrapping, U256};
use starknet_crypto::FieldElement;

use crate::errno::{Errno, Result};
use crate::exports::{parse_bigint, write_bigint, BigInt, MutBigInt};

pub unsafe fn parse_bigint_decimal(i: BigInt) -> Result<FieldElement> {
    if i.is_null() {
        return Err(Errno::InvalidNullPtr);
    }
    let s = CStr::from_ptr(i).to_str()?;
    Ok(FieldElement::from_dec_str(s)?)
}

#[repr(C)]
pub struct TransferMsg {
    /// decimal string
    pub amount: BigInt,
    /// decimal string
    pub nonce: BigInt,
    /// decimal string
    pub sender_vault_id: BigInt,
    /// hex string
    pub token: BigInt,
    /// decimal string
    pub receiver_vault_id: BigInt,
    /// hex string
    pub receiver_public_key: BigInt,
    /// decimal string
    pub expiration_time_stamp: BigInt,
    /// hex string, notice that condition could be nullable
    pub condition: BigInt,
}

/// Serializes the transfer message in the canonical format expected by the verifier.
/// ref: https://github.com/starkware-libs/starkware-crypto-utils/blob/d3a1e655105afd66ebc07f88a179a3042407cc7b/src/js/signature.js#L352-L418
#[no_mangle]
pub unsafe extern "C" fn get_transfer_msg_hash(msg: TransferMsg, hash: MutBigInt) -> Errno {
    let get_transfer_msg_hash_impl = move || {
        let amount = parse_bigint_decimal(msg.amount)?;
        let nonce = parse_bigint_decimal(msg.nonce)?;
        let sender_vault_id = parse_bigint_decimal(msg.sender_vault_id)?;
        let token = parse_bigint(msg.token)?;
        let receiver_vault_id = parse_bigint_decimal(msg.receiver_vault_id)?;
        let receiver_public_key = parse_bigint(msg.receiver_public_key)?;
        let expiration_time_stamp = parse_bigint_decimal(msg.expiration_time_stamp)?;
        let condition = if msg.condition.is_null() {
            Option::None
        } else {
            Option::Some(parse_bigint(msg.condition)?)
        };

        let instruction_type = if condition.is_none() {
            FieldElement::ONE
        } else {
            // actually I mean 2
            FieldElement::ONE + FieldElement::ONE
        };

        let result = hash_msg(
            instruction_type,
            sender_vault_id,
            receiver_vault_id,
            amount,
            FieldElement::ZERO,
            nonce,
            expiration_time_stamp,
            token,
            receiver_public_key,
            condition,
        )?;
        write_bigint(&result, hash);
        Ok::<_, Errno>(())
    };

    match get_transfer_msg_hash_impl() {
        Ok(_) => Errno::Ok,
        Err(e) => e,
    }
}

#[repr(C)]
pub struct LimitOrderMsg {
    /// decimal string
    pub vault_sell: BigInt,
    /// decimal string
    pub vault_buy: BigInt,
    /// decimal string
    pub amount_sell: BigInt,
    /// decimal string
    pub amount_buy: BigInt,
    /// hex string
    pub token_sell: BigInt,
    /// hex string
    pub token_buy: BigInt,
    /// decimal string
    pub nonce: BigInt,
    /// decimal string
    pub expiration_time_stamp: BigInt,
}

/// Serializes the order message in the canonical format expected by the verifier.
/// ref: https://github.com/starkware-libs/starkware-crypto-utils/blob/d3a1e655105afd66ebc07f88a179a3042407cc7b/src/js/signature.js#L226-L283
#[no_mangle]
pub unsafe extern "C" fn get_limit_order_msg_hash(msg: LimitOrderMsg, hash: MutBigInt) -> Errno {
    let get_limit_order_msg_hash_impl = move || {
        let vault_sell = parse_bigint_decimal(msg.vault_sell)?;
        let vault_buy = parse_bigint_decimal(msg.vault_buy)?;
        let amount_sell = parse_bigint_decimal(msg.amount_sell)?;
        let amount_buy = parse_bigint_decimal(msg.amount_buy)?;
        let token_sell = parse_bigint(msg.token_sell)?;
        let token_buy = parse_bigint(msg.token_buy)?;
        let nonce = parse_bigint_decimal(msg.nonce)?;
        let expiration_time_stamp = parse_bigint_decimal(msg.expiration_time_stamp)?;

        let result = hash_msg(
            FieldElement::ZERO,
            vault_sell,
            vault_buy,
            amount_sell,
            amount_buy,
            nonce,
            expiration_time_stamp,
            token_sell,
            token_buy,
            Option::None,
        )?;
        write_bigint(&result, hash);
        Ok::<_, Errno>(())
    };
    match get_limit_order_msg_hash_impl() {
        Ok(_) => Errno::Ok,
        Err(e) => e,
    }
}

trait FromFieldElement {
    fn from_fe(fe: &FieldElement) -> Self;
}

impl FromFieldElement for U256 {
    fn from_fe(fe: &FieldElement) -> Self {
        let bytes = fe.to_bytes_be();
        U256::from_be_bytes(bytes)
    }
}

/// ref: https://github.com/starkware-libs/starkware-crypto-utils/blob/d3a1e655105afd66ebc07f88a179a3042407cc7b/src/js/signature.js#L105
#[allow(clippy::too_many_arguments)]
fn hash_msg(
    instruction_type: FieldElement,
    vault0: FieldElement,
    vault1: FieldElement,
    amount0: FieldElement,
    amount1: FieldElement,
    nonce: FieldElement,
    expiration_time_stamp: FieldElement,
    token0: FieldElement,
    token1_or_pub_key: FieldElement,
    condition: Option<FieldElement>,
) -> Result<FieldElement> {
    let mut packaged_message: U256 = U256::from_fe(&instruction_type);
    packaged_message = (Wrapping(packaged_message << 31) + Wrapping(U256::from_fe(&vault0))).0;
    packaged_message = (Wrapping(packaged_message << 31) + Wrapping(U256::from_fe(&vault1))).0;
    packaged_message = (Wrapping(packaged_message << 63) + Wrapping(U256::from_fe(&amount0))).0;
    packaged_message = (Wrapping(packaged_message << 63) + Wrapping(U256::from_fe(&amount1))).0;
    packaged_message = (Wrapping(packaged_message << 31) + Wrapping(U256::from_fe(&nonce))).0;
    packaged_message =
        (Wrapping(packaged_message << 22) + Wrapping(U256::from_fe(&expiration_time_stamp))).0;
    let packaged_message = FieldElement::from_hex_be(format!("{packaged_message:x}").as_str())?;

    match condition {
        Some(value) => Result::Ok(starknet_crypto::pedersen_hash(
            &(starknet_crypto::pedersen_hash(
                &(starknet_crypto::pedersen_hash(&token0, &token1_or_pub_key)),
                &value,
            )),
            &packaged_message,
        )),
        None => Result::Ok(starknet_crypto::pedersen_hash(
            &(starknet_crypto::pedersen_hash(&token0, &token1_or_pub_key)),
            &packaged_message,
        )),
    }
}
#[cfg(test)]
mod tests {
    use std::ffi::{c_char, CStr, CString};
    use std::ptr::null;

    use super::{get_limit_order_msg_hash, get_transfer_msg_hash, LimitOrderMsg, TransferMsg};
    use crate::exports::BIG_INT_SIZE;

    /// ref: https://github.com/starkware-libs/starkex-resources/blob/844ac3dcb1f735451457f7eecc6e37cd96d1cb2d/crypto/starkware/crypto/signature/signature_test_data.json#L38
    #[test]
    fn test_get_transfer_msg_hash() -> anyhow::Result<()> {
        let buffer: *mut c_char = ([0 as c_char; BIG_INT_SIZE]).as_mut_ptr();
        unsafe {
            let errno = get_transfer_msg_hash(
                TransferMsg {
                    amount: CString::new("2154549703648910716").unwrap().into_raw(),
                    nonce: CString::new("1").unwrap().into_raw(),
                    sender_vault_id: CString::new("34").unwrap().into_raw(),
                    token: CString::new(
                        "0x3003a65651d3b9fb2eff934a4416db301afd112a8492aaf8d7297fc87dcd9f4",
                    )
                    .unwrap()
                    .into_raw(),
                    receiver_vault_id: CString::new("21").unwrap().into_raw(),
                    receiver_public_key: CString::new(
                        "0x5fa3383597691ea9d827a79e1a4f0f7949435ced18ca9619de8ab97e661020",
                    )
                    .unwrap()
                    .into_raw(),
                    expiration_time_stamp: CString::new("438953").unwrap().into_raw(),
                    condition: null(),
                },
                buffer,
            );
            assert_eq!(errno as u8, 0);

            let result = CStr::from_ptr(buffer).to_str()?;
            assert_eq!(
                result,
                "6366b00c218fb4c8a8b142ca482145e8513c78e00faa0de76298ba14fc37ae7"
            )
        }
        Ok(())
    }
    #[test]
    fn test_get_transfer_msg_hash_without_0x_prefix() -> anyhow::Result<()> {
        let buffer: *mut c_char = ([0 as c_char; BIG_INT_SIZE]).as_mut_ptr();
        unsafe {
            let errno = get_transfer_msg_hash(
                TransferMsg {
                    amount: CString::new("2154549703648910716").unwrap().into_raw(),
                    nonce: CString::new("1").unwrap().into_raw(),
                    sender_vault_id: CString::new("34").unwrap().into_raw(),
                    token: CString::new(
                        "3003a65651d3b9fb2eff934a4416db301afd112a8492aaf8d7297fc87dcd9f4",
                    )
                    .unwrap()
                    .into_raw(),
                    receiver_vault_id: CString::new("21").unwrap().into_raw(),
                    receiver_public_key: CString::new(
                        "5fa3383597691ea9d827a79e1a4f0f7949435ced18ca9619de8ab97e661020",
                    )
                    .unwrap()
                    .into_raw(),
                    expiration_time_stamp: CString::new("438953").unwrap().into_raw(),
                    condition: null(),
                },
                buffer,
            );
            assert_eq!(errno as u8, 0);

            let result = CStr::from_ptr(buffer).to_str()?;
            assert_eq!(
                result,
                "6366b00c218fb4c8a8b142ca482145e8513c78e00faa0de76298ba14fc37ae7"
            )
        }
        Ok(())
    }

    /// ref: https://github.com/starkware-libs/starkex-resources/blob/master/crypto/starkware/crypto/signature/signature_test_data.json#L3
    #[test]
    fn test_get_limit_order_msg_hash_party_a_order() -> anyhow::Result<()> {
        let buffer: *mut c_char = ([0 as c_char; BIG_INT_SIZE]).as_mut_ptr();

        unsafe {
            let errno = get_limit_order_msg_hash(
                LimitOrderMsg {
                    vault_sell: CString::new("21").unwrap().into_raw(),
                    vault_buy: CString::new("27").unwrap().into_raw(),
                    amount_sell: CString::new("2154686749748910716").unwrap().into_raw(),
                    amount_buy: CString::new("1470242115489520459").unwrap().into_raw(),
                    token_sell: CString::new(
                        "0x5fa3383597691ea9d827a79e1a4f0f7989c35ced18ca9619de8ab97e661020",
                    )
                    .unwrap()
                    .into_raw(),
                    token_buy: CString::new(
                        "0x774961c824a3b0fb3d2965f01471c9c7734bf8dbde659e0c08dca2ef18d56a",
                    )
                    .unwrap()
                    .into_raw(),
                    nonce: CString::new("0").unwrap().into_raw(),
                    expiration_time_stamp: CString::new("438953").unwrap().into_raw(),
                },
                buffer,
            );
            assert_eq!(errno as u8, 0);

            let result = CStr::from_ptr(buffer).to_str()?;
            assert_eq!(
                result,
                "397e76d1667c4454bfb83514e120583af836f8e32a516765497823eabe16a3f"
            )
        }
        Ok(())
    }

    /// ref: https://github.com/starkware-libs/starkex-resources/blob/844ac3dcb1f735451457f7eecc6e37cd96d1cb2d/crypto/starkware/crypto/signature/signature_test_data.json#L18
    #[test]
    fn test_get_limit_order_msg_hash_party_b_order() -> anyhow::Result<()> {
        let buffer: *mut c_char = ([0 as c_char; BIG_INT_SIZE]).as_mut_ptr();

        unsafe {
            let errno = get_limit_order_msg_hash(
                LimitOrderMsg {
                    vault_sell: CString::new("221").unwrap().into_raw(),
                    vault_buy: CString::new("227").unwrap().into_raw(),
                    amount_buy: CString::new("21546867497489").unwrap().into_raw(),
                    amount_sell: CString::new("14702421154895").unwrap().into_raw(),
                    token_buy: CString::new(
                        "0x5fa3383597691ea9d827a79e1a4f0f7989c35ced18ca9619de8ab97e661020",
                    )
                    .unwrap()
                    .into_raw(),
                    token_sell: CString::new(
                        "0x774961c824a3b0fb3d2965f01471c9c7734bf8dbde659e0c08dca2ef18d56a",
                    )
                    .unwrap()
                    .into_raw(),
                    nonce: CString::new("1").unwrap().into_raw(),
                    expiration_time_stamp: CString::new("468963").unwrap().into_raw(),
                },
                buffer,
            );
            assert_eq!(errno as u8, 0);

            let result = CStr::from_ptr(buffer).to_str()?;
            assert_eq!(
                result,
                "6adb14408452ede28b89f40ca1847eca4de6a2dd6eb2c7d6dc5584f9399586"
            )
        }
        Ok(())
    }
}
