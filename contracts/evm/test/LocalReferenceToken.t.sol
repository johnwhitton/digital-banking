// SPDX-License-Identifier: MIT
pragma solidity 0.8.25;

import {LocalReferenceToken} from "../src/LocalReferenceToken.sol";

interface Vm {
    struct Log {
        bytes32[] topics;
        bytes data;
        address emitter;
    }

    function recordLogs() external;

    function getRecordedLogs() external returns (Log[] memory logs);
}

contract MintActor {
    function mint(LocalReferenceToken token, address recipient, uint256 amount) external {
        token.mint(recipient, amount);
    }
}

contract LocalReferenceTokenTest {
    Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));

    address private constant RECIPIENT = address(0x3003);
    uint256 private constant AMOUNT = 12_345;

    LocalReferenceToken private token;
    MintActor private minter;

    function setUp() public {
        token = new LocalReferenceToken(address(this));
        minter = new MintActor();
        token.grantRole(token.MINTER_ROLE(), address(minter));
    }

    function testAuthorizedMintChangesExactBalanceAndSupply() public {
        minter.mint(token, RECIPIENT, AMOUNT);

        require(token.balanceOf(RECIPIENT) == AMOUNT, "recipient balance mismatch");
        require(token.totalSupply() == AMOUNT, "total supply mismatch");
        require(token.decimals() == 2, "asset-unit scale mismatch");
    }

    function testMintEmitsExactTransferEvent() public {
        vm.recordLogs();
        minter.mint(token, RECIPIENT, AMOUNT);

        Vm.Log[] memory logs = vm.getRecordedLogs();
        bytes32 transferTopic = keccak256("Transfer(address,address,uint256)");
        bool matched;
        for (uint256 index = 0; index < logs.length; index++) {
            if (logs[index].emitter == address(token)
                && logs[index].topics.length == 3
                && logs[index].topics[0] == transferTopic
                && logs[index].topics[1] == bytes32(0)
                && logs[index].topics[2] == bytes32(uint256(uint160(RECIPIENT)))
                && abi.decode(logs[index].data, (uint256)) == AMOUNT) {
                matched = true;
            }
        }
        require(matched, "exact mint event missing");
    }

    function testUnauthorizedMintFailsWithoutStateChange() public {
        try token.mint(RECIPIENT, AMOUNT) {
            revert("unauthorized mint succeeded");
        } catch {
            require(token.balanceOf(RECIPIENT) == 0, "unauthorized balance changed");
            require(token.totalSupply() == 0, "unauthorized supply changed");
        }
    }

    function testZeroAddressMintFails() public {
        try minter.mint(token, address(0), AMOUNT) {
            revert("zero-address mint succeeded");
        } catch {
            require(token.totalSupply() == 0, "zero-address mint changed supply");
        }
    }

    function testAdminAndMinterRolesAreSeparate() public view {
        require(token.hasRole(token.DEFAULT_ADMIN_ROLE(), address(this)), "admin role missing");
        require(!token.hasRole(token.MINTER_ROLE(), address(this)), "admin is implicit minter");
        require(token.hasRole(token.MINTER_ROLE(), address(minter)), "minter role missing");
        require(!token.hasRole(token.DEFAULT_ADMIN_ROLE(), address(minter)), "minter is admin");
    }
}
