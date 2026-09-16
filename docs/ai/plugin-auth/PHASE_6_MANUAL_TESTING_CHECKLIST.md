# Manual Testing Checklist - Plugin Auth (Phase 6)

**Feature**: User Account Management  
**Repository**: knk-plugin  
**Date**: January 30, 2026  
**Tester**: _________________

---

## Pre-Test Setup

### Environment Verification
- [ ] Backend API (knk-web-api) is running and accessible
- [ ] Test database is properly seeded
- [ ] Minecraft dev server (1.21.10) is running
- [ ] Plugin JAR deployed to server plugins folder
- [ ] Server logs accessible for debugging

### Configuration Validation
- [ ] Verify `config.yml` has correct API base URL
- [ ] Verify account timeout settings (120 seconds)
- [ ] Verify link code expiry (20 minutes)
- [ ] Verify message templates are properly formatted

---

## Test Scenarios

### 1. Player Join - New Account Creation

**Scenario**: New player joins server for first time

**Steps**:
1. Join server with a new Minecraft account
2. Observe join messages

**Expected Results**:
- [ ] Player joins successfully without errors
- [ ] Welcome message displayed with username
- [ ] Account linking suggestion shown
- [ ] Balance displayed (0 coins, 0 gems, 0 XP for new account)
- [ ] Server logs show "Creating minimal user account" message
- [ ] Server logs show "Cached user data" message

**Notes**: _________________________________________________________________

---

### 2. Player Join - Existing Account

**Scenario**: Player with existing account rejoins server

**Steps**:
1. Join server with an account that has been created previously
2. Observe join messages

**Expected Results**:
- [ ] Player joins successfully
- [ ] Welcome message: "Welcome back, [username]!"
- [ ] Current balance displayed correctly
- [ ] No duplicate account warning shown
- [ ] Server logs show "No duplicate found"

**Notes**: _________________________________________________________________

---

### 3. /account create - Valid Input

**Scenario**: Player creates account with email and password

**Steps**:
1. Run `/account create`
2. Enter email: `test@example.com`
3. Enter password: `StrongPassword123`
4. Confirm password: `StrongPassword123`

**Expected Results**:
- [ ] Command accepted
- [ ] Step 1/3 prompt for email shown
- [ ] Email accepted, Step 2/3 prompt for password shown
- [ ] Password accepted, Step 3/3 prompt for confirmation shown
- [ ] Confirmation accepted
- [ ] Success message: "Account created successfully!"
- [ ] Chat input not broadcasted to other players
- [ ] Server logs show "Account created" message
- [ ] Backend database has email and password hash

**Notes**: _________________________________________________________________

---

### 4. /account create - Invalid Email

**Scenario**: Player enters invalid email format

**Steps**:
1. Run `/account create`
2. Enter email: `not-an-email`

**Expected Results**:
- [ ] Error message: "Invalid email format. Please try again."
- [ ] Session remains active (can retry)
- [ ] Still on Step 1/3

**Notes**: _________________________________________________________________

---

### 5. /account create - Weak Password

**Scenario**: Player enters password less than 8 characters

**Steps**:
1. Run `/account create`
2. Enter email: `test@example.com`
3. Enter password: `weak`

**Expected Results**:
- [ ] Error message: "Password must be at least 8 characters. Try again."
- [ ] Session remains active
- [ ] Remains on Step 2/3

**Notes**: _________________________________________________________________

---

### 6. /account create - Password Mismatch

**Scenario**: Player enters non-matching passwords

**Steps**:
1. Run `/account create`
2. Enter email: `test@example.com`
3. Enter password: `Password123`
4. Confirm password: `DifferentPassword456`

**Expected Results**:
- [ ] Error message: "Passwords don't match. Starting over..."
- [ ] Session resets to Step 2/3 (keep email, re-enter password)
- [ ] Session remains active

**Notes**: _________________________________________________________________

---

### 7. /account create - Cancel Flow

**Scenario**: Player cancels account creation

**Steps**:
1. Run `/account create`
2. Type `cancel`

**Expected Results**:
- [ ] Message: "Cancelled."
- [ ] Session closed
- [ ] No changes to account
- [ ] Can run `/account create` again

**Notes**: _________________________________________________________________

---

### 8. /account create - Timeout

**Scenario**: Player starts creation but doesn't complete within timeout

**Steps**:
1. Run `/account create`
2. Wait 120+ seconds without entering anything

**Expected Results**:
- [ ] Timeout message: "Input timeout. Please start over."
- [ ] Session automatically closed
- [ ] Can run `/account create` again

**Notes**: _________________________________________________________________

---

### 9. /account create - Already Has Email

**Scenario**: Player with linked email tries to create account

**Steps**:
1. Ensure player has email linked
2. Run `/account create`

**Expected Results**:
- [ ] Error message: "You already have an email linked!"
- [ ] Suggestion to use `/account` to view account
- [ ] No chat capture started

**Notes**: _________________________________________________________________

---

### 10. /account link - Generate Code

**Scenario**: Player generates link code for web app

**Steps**:
1. Run `/account link` (no arguments)

**Expected Results**:
- [ ] Link code displayed (format: ABC-123-DEF-456)
- [ ] Expiry time shown (20 minutes)
- [ ] Instructions to use code in web app
- [ ] Server logs show "Generated link code" message
- [ ] Backend database has link code entry

**Notes**: _________________________________________________________________

---

### 11. /account link - Valid Code

**Scenario**: Player links account using valid code from web app

**Steps**:
1. Generate link code in web app (create account there first)
2. In-game, run `/account link ABC123DEF456`

**Expected Results**:
- [ ] Code validated
- [ ] Account linked successfully
- [ ] Success message: "Your accounts have been linked!"
- [ ] Balance updated to match web app account
- [ ] Email shown in `/account` output
- [ ] Server logs show "Account linked"

**Notes**: _________________________________________________________________

---

### 12. /account link - Invalid Code

**Scenario**: Player enters expired or invalid link code

**Steps**:
1. Run `/account link INVALIDCODE`

**Expected Results**:
- [ ] Error message: "Invalid/expired code. Use /account link for new one."
- [ ] No account changes
- [ ] Session not started

**Notes**: _________________________________________________________________

---

### 13. /account link - Duplicate Detected (Merge Flow)

**Scenario**: Player links account but duplicate detected

**Steps**:
1. Create account in web app with email
2. Join Minecraft server (creates minimal account)
3. Run `/account link <code from web app>`

**Expected Results**:
- [ ] Duplicate detection triggered
- [ ] Merge UI displayed showing:
  - Account A details (coins, gems, XP, email)
  - Account B details (coins, gems, XP, email)
- [ ] Prompt to choose A or B
- [ ] Chat capture started

**Notes**: _________________________________________________________________

---

### 14. Account Merge - Choose Account A

**Scenario**: Player chooses Account A during merge

**Steps**:
1. Trigger merge flow (see test #13)
2. Type `A`

**Expected Results**:
- [ ] Merge completes successfully
- [ ] Account A becomes primary
- [ ] Account B data merged into Account A
- [ ] Success message shows combined balances
- [ ] Server logs show "Account merge complete"
- [ ] Backend database shows merged account

**Notes**: _________________________________________________________________

---

### 15. Account Merge - Choose Account B

**Scenario**: Player chooses Account B during merge

**Steps**:
1. Trigger merge flow
2. Type `B`

**Expected Results**:
- [ ] Merge completes successfully
- [ ] Account B becomes primary
- [ ] Account A data merged into Account B
- [ ] Success message shows combined balances
- [ ] Database updated correctly

**Notes**: _________________________________________________________________

---

### 16. Account Merge - Invalid Choice

**Scenario**: Player enters invalid choice during merge

**Steps**:
1. Trigger merge flow
2. Type `C`

**Expected Results**:
- [ ] Error message: "Please type 'A' or 'B'"
- [ ] Session remains active
- [ ] Can retry with valid choice

**Notes**: _________________________________________________________________

---

### 17. /account - Display Status

**Scenario**: Player views account information

**Steps**:
1. Run `/account`

**Expected Results**:
- [ ] Account summary displayed:
  - Username
  - UUID
  - Email (or "Not linked")
  - Coins
  - Gems
  - Experience Points
- [ ] If no email: suggestion to use `/account create` or `/account link`
- [ ] If duplicate: warning shown

**Notes**: _________________________________________________________________

---

### 18. Network Error Handling

**Scenario**: API is unavailable during operation

**Steps**:
1. Stop backend API
2. Join server OR run account command

**Expected Results**:
- [ ] User-friendly error message shown
- [ ] No server crash or exception spam
- [ ] Server logs show detailed error (for admins)
- [ ] Player can retry when API is back online

**Notes**: _________________________________________________________________

---

### 19. API Timeout Handling

**Scenario**: API responds slowly

**Steps**:
1. Simulate slow API (delay in backend)
2. Run account command

**Expected Results**:
- [ ] Command doesn't hang indefinitely
- [ ] Timeout after configured period
- [ ] Error message shown to player
- [ ] Server remains responsive

**Notes**: _________________________________________________________________

---

### 20. Concurrent Player Sessions

**Scenario**: Multiple players using account commands simultaneously

**Steps**:
1. Have 2+ players run `/account create` at same time
2. Each completes their flow

**Expected Results**:
- [ ] All sessions handled independently
- [ ] No cross-talk between sessions
- [ ] No cache conflicts
- [ ] All accounts created successfully

**Notes**: _________________________________________________________________

---

### 21. Player Quit During Chat Capture

**Scenario**: Player quits mid-flow

**Steps**:
1. Run `/account create`
2. Enter email
3. Quit server before completing

**Expected Results**:
- [ ] Session cleaned up on quit
- [ ] No memory leak
- [ ] Player can rejoin and start new session
- [ ] No lingering chat capture state

**Notes**: _________________________________________________________________

---

### 22. Email Validation Edge Cases

**Scenario**: Test various email formats

**Test Emails**:
- `user@domain.com` ✓
- `user.name@domain.com` ✓
- `user+tag@domain.co.uk` ✓
- `UPPERCASE@DOMAIN.COM` ✓
- `user@` ✗
- `@domain.com` ✗
- `user domain@domain.com` ✗
- `user@domain` ✗

**Expected Results**:
- [ ] Valid emails accepted (✓)
- [ ] Invalid emails rejected (✗)
- [ ] Clear error messages for rejections

**Notes**: _________________________________________________________________

---

### 23. Password Validation Edge Cases

**Scenario**: Test password strength requirements

**Test Passwords**:
- `12345678` ✓ (8 chars)
- `Password123` ✓ (11 chars)
- `!@#$%^&*()` ✓ (special chars)
- `1234567` ✗ (7 chars)
- `short` ✗ (5 chars)
- `` (empty) ✗

**Expected Results**:
- [ ] Passwords 8+ chars accepted (✓)
- [ ] Passwords < 8 chars rejected (✗)
- [ ] Clear error messages for rejections

**Notes**: _________________________________________________________________

---

### 24. Permission Checks

**Scenario**: Verify permission system

**Steps**:
1. Remove `knk.account.use` permission from player
2. Try to run account commands

**Expected Results**:
- [ ] Commands blocked if no permission
- [ ] Appropriate "no permission" message shown
- [ ] Admin commands require `knk.account.admin`

**Notes**: _________________________________________________________________

---

### 25. Rate Limiting

**Scenario**: Prevent command spam

**Steps**:
1. Run `/account create`
2. Cancel immediately
3. Run `/account create` again within cooldown period

**Expected Results**:
- [ ] Cooldown message shown
- [ ] Cannot spam commands
- [ ] Cooldown period configurable (default: 30 seconds)

**Notes**: _________________________________________________________________

---

## Performance Tests

### Load Test - Player Join

**Steps**:
1. Have 10+ players join simultaneously

**Expected Results**:
- [ ] All players synced successfully
- [ ] No significant lag
- [ ] No API rate limit errors
- [ ] Server TPS remains stable

**Notes**: _________________________________________________________________

---

### Memory Leak Test

**Steps**:
1. Have players join, create accounts, quit
2. Repeat 50+ times
3. Monitor server memory

**Expected Results**:
- [ ] Memory usage remains stable
- [ ] Cache properly cleared on quit
- [ ] No session object leaks

**Notes**: _________________________________________________________________

---

## Bug Reports

### Issue #1
**Description**: _____________________________________________________________
**Steps to Reproduce**: _______________________________________________________
**Expected**: ________________________________________________________________
**Actual**: __________________________________________________________________
**Severity**: ☐ Critical  ☐ High  ☐ Medium  ☐ Low

---

### Issue #2
**Description**: _____________________________________________________________
**Steps to Reproduce**: _______________________________________________________
**Expected**: ________________________________________________________________
**Actual**: __________________________________________________________________
**Severity**: ☐ Critical  ☐ High  ☐ Medium  ☐ Low

---

## Test Summary

**Date Completed**: _________________  
**Total Scenarios**: 25  
**Passed**: _____  
**Failed**: _____  
**Blocked**: _____  
**Pass Rate**: _____%

**Overall Assessment**: ☐ Ready for Production  ☐ Needs Minor Fixes  ☐ Needs Major Fixes

**Notes**: ___________________________________________________________________

____________________________________________________________________________

____________________________________________________________________________

**Tester Signature**: _________________  **Date**: _________________
