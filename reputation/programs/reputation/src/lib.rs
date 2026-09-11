use anchor_lang::prelude::*;

declare_id!("AdJRTSZ9pBrgzHsKErZZUruRkqVG929zsUPNrJeo3dBM");

/// ClearSign community reputation registry.
///
/// A decentralized "Trustpilot for wallets": anyone can stake and vote that a
/// target address is trustworthy (+1) or a scam (-1). Votes are **stake-weighted**
/// (skin-in-the-game → sybil resistance) and one record is kept per (target,
/// voter). ClearSign reads the aggregate for a counterparty at sign-time and
/// raises a risk when the community has flagged it.
///
/// v1 stakes native lamports so the whole flow deploys and demos with zero SPL
/// ceremony; the stake asset is meant to become the SKR token (swap the lamport
/// moves for an SPL vault transfer — the account layout below is unchanged).
#[program]
pub mod reputation {
    use super::*;

    /// Cast (or update) a stake-weighted vote on `target`.
    /// verdict: +1 = trust, -1 = scam. amount = lamports to stake (must be > 0).
    pub fn vote(ctx: Context<Vote>, target: Pubkey, verdict: i8, amount: u64) -> Result<()> {
        require!(verdict == 1 || verdict == -1, RepError::BadVerdict);
        require!(amount > 0, RepError::ZeroStake);

        let now = Clock::get()?.unix_timestamp;

        // Move the stake from the voter into the reputation PDA (which holds the vault).
        anchor_lang::system_program::transfer(
            CpiContext::new(
                ctx.accounts.system_program.key(),
                anchor_lang::system_program::Transfer {
                    from: ctx.accounts.voter.to_account_info(),
                    to: ctx.accounts.reputation.to_account_info(),
                },
            ),
            amount,
        )?;

        let rep = &mut ctx.accounts.reputation;
        let rec = &mut ctx.accounts.voter_record;

        // First time this PDA is used → initialize identity + timestamps.
        if rep.target == Pubkey::default() {
            rep.target = target;
            rep.first_seen = now;
            rep.bump = ctx.bumps.reputation;
        }
        rep.last_seen = now;

        // A brand-new voter record → count a distinct voter. Otherwise first back
        // out their previous weight before applying the new one (re-vote).
        if rec.voter == Pubkey::default() {
            rec.voter = ctx.accounts.voter.key();
            rec.target = target;
            rec.bump = ctx.bumps.voter_record;
            rep.voters = rep.voters.saturating_add(1);
        } else {
            match rec.verdict {
                1 => rep.up = rep.up.saturating_sub(rec.stake),
                -1 => rep.down = rep.down.saturating_sub(rec.stake),
                _ => {}
            }
        }

        rec.verdict = verdict;
        rec.stake = rec.stake.saturating_add(amount);
        rep.staked = rep.staked.saturating_add(amount);
        match verdict {
            1 => rep.up = rep.up.saturating_add(rec.stake),
            -1 => rep.down = rep.down.saturating_add(rec.stake),
            _ => {}
        }
        Ok(())
    }

    /// Withdraw the caller's whole stake and remove their vote weight.
    pub fn unstake(ctx: Context<Unstake>, _target: Pubkey) -> Result<()> {
        let refund = ctx.accounts.voter_record.stake;
        require!(refund > 0, RepError::ZeroStake);

        let rep = &mut ctx.accounts.reputation;
        // Program-owned PDA → move lamports by direct balance edit (can't system-transfer out).
        **rep.to_account_info().try_borrow_mut_lamports()? -= refund;
        **ctx.accounts.voter.to_account_info().try_borrow_mut_lamports()? += refund;

        match ctx.accounts.voter_record.verdict {
            1 => rep.up = rep.up.saturating_sub(refund),
            -1 => rep.down = rep.down.saturating_sub(refund),
            _ => {}
        }
        rep.staked = rep.staked.saturating_sub(refund);
        rep.voters = rep.voters.saturating_sub(1);
        // voter_record is closed (rent returned to voter) via the `close` attribute.
        Ok(())
    }
}

#[derive(Accounts)]
#[instruction(target: Pubkey)]
pub struct Vote<'info> {
    #[account(
        init_if_needed,
        payer = voter,
        space = 8 + Reputation::LEN,
        seeds = [b"rep", target.as_ref()],
        bump,
    )]
    pub reputation: Account<'info, Reputation>,
    #[account(
        init_if_needed,
        payer = voter,
        space = 8 + VoterRecord::LEN,
        seeds = [b"voter", target.as_ref(), voter.key().as_ref()],
        bump,
    )]
    pub voter_record: Account<'info, VoterRecord>,
    #[account(mut)]
    pub voter: Signer<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(target: Pubkey)]
pub struct Unstake<'info> {
    #[account(mut, seeds = [b"rep", target.as_ref()], bump = reputation.bump)]
    pub reputation: Account<'info, Reputation>,
    #[account(
        mut,
        close = voter,
        seeds = [b"voter", target.as_ref(), voter.key().as_ref()],
        bump = voter_record.bump,
        has_one = voter,
    )]
    pub voter_record: Account<'info, VoterRecord>,
    #[account(mut)]
    pub voter: Signer<'info>,
}

/// Aggregate reputation for one target address. Read by ClearSign at sign-time.
#[account]
pub struct Reputation {
    pub target: Pubkey,   // 32
    pub up: u64,          // 8  stake-weighted trust
    pub down: u64,        // 8  stake-weighted scam
    pub staked: u64,      // 8  total currently staked
    pub voters: u32,      // 4  distinct voters
    pub first_seen: i64,  // 8
    pub last_seen: i64,   // 8
    pub bump: u8,         // 1
}
impl Reputation {
    pub const LEN: usize = 32 + 8 + 8 + 8 + 4 + 8 + 8 + 1;
}

/// One voter's standing vote on one target (enforces one vote per voter).
#[account]
pub struct VoterRecord {
    pub voter: Pubkey,  // 32
    pub target: Pubkey, // 32
    pub stake: u64,     // 8
    pub verdict: i8,    // 1
    pub bump: u8,       // 1
}
impl VoterRecord {
    pub const LEN: usize = 32 + 32 + 8 + 1 + 1;
}

#[error_code]
pub enum RepError {
    #[msg("verdict must be +1 (trust) or -1 (scam)")]
    BadVerdict,
    #[msg("stake amount must be greater than zero")]
    ZeroStake,
}
