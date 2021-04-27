package com.nest.ib.service.serviceImpl;

import com.nest.ib.config.NestProperties;
import com.nest.ib.contract.PriceSheetView;
import com.nest.ib.helper.WalletHelper;
import com.nest.ib.state.GasPriceState;
import com.nest.ib.utils.EthClient;
import com.nest.ib.model.*;
import com.nest.ib.service.*;
import com.nest.ib.state.Erc20State;
import com.nest.ib.state.MinnerState;
import com.nest.ib.utils.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;


import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutionException;

@Service
public class MiningServiceImpl implements MiningService {
    private static final Logger log = LoggerFactory.getLogger(MiningServiceImpl.class);

    private static final String POST2 = "post2";
    private static final String POST = "post";

    @Autowired
    private EthClient ethClient;
    @Autowired
    private Erc20State erc20State;
    @Autowired
    private MinnerState minnerState;
    @Autowired
    private WalletHelper walletHelper;
    @Autowired
    private GasPriceState gasPriceState;
    @Autowired
    private NestProperties nestProperties;

    @Override
    public void offer(Wallet wallet) {
        if (!minnerState.isOpen()) {
            log.info("The miner has not been started yet");
            return;
        }

        // If MUST_POST2 is false, then each time the issue of the nToken is checked to see if it has reached 5 million, then the post2 method must be called
        if (!minnerState.isMustPost2()) {
            if (ethClient.needOpenPost2()) {
                // Authorization check is required after opening
                log.info("To enable POST2 quotes, {} authorization needs to begin", erc20State.nToken.getSymbol());

                BigInteger nonce = ethClient.ethGetTransactionCount(wallet.getCredentials().getAddress());
                if (nonce == null) {
                    log.error("{} Failed to get nonce：{}", erc20State.nToken.getSymbol(), nonce);
                    return;
                }

                String transactionHash = null;
                try {
                    transactionHash = ethClient.erc20Appprove(wallet, erc20State.nToken, nonce);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                boolean ok = ethClient.checkTxStatus(transactionHash, nonce, wallet.getCredentials().getAddress());
                if (ok) {
                    log.info("{} Authorization success", erc20State.nToken.getSymbol());
                    minnerState.setMustPost2(true);
                } else {
                    log.error("*****{} Authorization transaction failed, unable to quote, mining machine will be shut down!!!!!*****", erc20State.nToken.getSymbol());
                    minnerState.closeMiner();
                }
            }
        }

        // Get the last quoted block
        BigInteger miningBlockNumber = ethClient.checkLatestMining();
        BigInteger ethBlockNumber = ethClient.ethBlockNumber();
        if (ethBlockNumber == null || miningBlockNumber == null) {
            log.info("Failed to obtain the latest block number or the latest quote block number：{}，{}", ethBlockNumber, miningBlockNumber);
            return;
        }

        // Check that the set block interval is met
        if (!meetBlockInterval(ethBlockNumber, miningBlockNumber)) {
            return;
        }

        if (!updatePrice()) {
            log.error("Price update failed");
            return;
        }

        // Check the unfrozen balance and account balance in the contract, calculate the assets required by the quotation, and judge whether the balance is sufficient
        walletHelper.updateBalance(wallet, false);
        if (!wallet.isRich()) {
            return;
        }

        BigInteger offerGasPrice = ethClient.ethGasPrice(gasPriceState.baseOfferType);
        BigInteger nonce = ethClient.ethGetTransactionCount(wallet.getCredentials().getAddress());

        if (offerGasPrice == null || nonce == null) {
            log.error("Failed to get a quote GasPrice or nonce：{}，{}", offerGasPrice, nonce);
            return;
        }

        String transactionHash = null;

        if (wallet.getOfferNeed().isNull(minnerState.isMustPost2())) return;
        // Post2 quotation
        if (minnerState.isMustPost2()) {
            transactionHash = post2(wallet, offerGasPrice, nonce);
        } else {
            // Post quotation
            transactionHash = post(wallet, offerGasPrice, nonce);
        }

        log.info("hash:{}", transactionHash);

        if (StringUtils.isEmpty(transactionHash)) {
            return;
        }

        // heck Quotation Transaction Status
        checkOfferTransaction(wallet, offerGasPrice, nonce, miningBlockNumber);

    }

    /**
     * Unfreeze assets in bulk
     *
     * @param wallet
     */
    @Override
    public void closePriceSheets(Wallet wallet) {
        String address = wallet.getCredentials().getAddress();
        // Close the token quotation in bulk
        Erc20State.Item token = erc20State.token;
        Erc20State.Item ntoken = erc20State.nToken;
        BigInteger nonce = ethClient.ethGetTransactionCount(address);
        if (nonce == null) {
            log.error("{} ：closeList failed to get nonce", address);
            return;
        }

        boolean rich = walletHelper.updateBalance(wallet, false);

        List<Uint256> canClosedTokenSheetIndexs = canClosedSheetIndexs(wallet, nonce, token, rich);
        List<Uint256> canClosedNtokenSheetIndexs = canClosedSheetIndexs(wallet, nonce, ntoken, rich);
        if (!CollectionUtils.isEmpty(canClosedTokenSheetIndexs) && !CollectionUtils.isEmpty(canClosedNtokenSheetIndexs)) {
            String hash = ethClient.closeList2(wallet, nonce, token.getAddress(), canClosedTokenSheetIndexs, canClosedNtokenSheetIndexs);
            log.info("Defrost the hash of the token and Ntoken assets in bulk：{}", hash);
        } else if (!CollectionUtils.isEmpty(canClosedTokenSheetIndexs)) {
            String closeTokenHash = close(wallet, nonce, token.getAddress(), canClosedTokenSheetIndexs);
            log.info("closeTokenHash ：{}", closeTokenHash);
        } else if (!CollectionUtils.isEmpty(canClosedNtokenSheetIndexs)) {
            String closeNTokenHash = close(wallet, nonce, ntoken.getAddress(), canClosedNtokenSheetIndexs);
            log.info("closeNTokenHash ：{}", closeNTokenHash);
        }
    }

    private String close(Wallet wallet, BigInteger nonce, String erc20Address, List<Uint256> indices) {
        int size1 = indices.size();
        if (size1 == 1) {
            String closeHash = ethClient.close(wallet, nonce, erc20Address, new Uint256(indices.get(0).getValue()));
            return closeHash;
        }
        String hash = ethClient.closeList(wallet, nonce, erc20Address, indices);
        return hash;
    }

    private List<Uint256> canClosedSheetIndexs(Wallet wallet, BigInteger nonce, Erc20State.Item erc20, boolean rich) {
        // Obtain unthawed quotations
        List<PriceSheetView> priceSheets = ethClient.unClosedSheetListOf(wallet.getCredentials().getAddress(), erc20.getAddress(), minnerState.getMaxFindNum());

        // Get quotes that can be defrosted
        List<Uint256> canClosedSheetIndexs = ethClient.canClosedSheetIndexs(priceSheets);
        int size1 = CollectionUtils.isEmpty(canClosedSheetIndexs) ? 0 : canClosedSheetIndexs.size();
        if (size1 == 0) return null;

        // Whether to wait for the next thaw
        boolean wait = true;
        if (size1 < minnerState.getCloseMinNum()) {
            log.info("{} Quantity of quotation sheet that can be defrosted: {}, the minimum defrosted quantity is not reached:{}", erc20.getSymbol(), size1, minnerState.getCloseMinNum());

            // Gets the largest index of the current quotation list
            BigInteger nowMaxIndex = ethClient.lastIndex(erc20.getAddress());
            if (nowMaxIndex == null) {
                wait = false;
                log.info("Quotation list length retrieval failed. Must be defrosted");
            }

            if (size1 > 0 && wait) {
                BigInteger farthestIndex = canClosedSheetIndexs.get(size1 - 1).getValue();
                // Maximum number of queries -10
                BigInteger subtract = minnerState.getMaxFindNum().subtract(BigInteger.TEN);
                // The largest index of the current quotation list - the index of the last quotation that can be defrosted
                BigInteger subtract1 = nowMaxIndex.subtract(farthestIndex);
                if (subtract1.compareTo(subtract) >= 0) {
                    // If the difference between the largest index of the current quotation list and the index of the last quotation list is greater than the maximum number of queries -10,
                    // it must be defrosted at this time to avoid that the quotation list cannot be defrosted by subsequent queries
                    wait = false;
                    log.info("This quotation is so old that it must be defrosted");
                }
            }

            if (!rich) {
                wait = false;
                log.info("The available assets of the account are not quoted enough and must be unfrozen. At this time, the amount of assets that can be unfrozen is: {}", size1);
            }
        } else {
            wait = false;
        }

        if (wait) return null;

        return canClosedSheetIndexs;
    }

    /**
     * Update the exchange price to calculate the number of ERC20 and NTokens needed to specify the ETH quote
     */
    public boolean updatePrice() {
        boolean result;

        boolean ok = erc20State.updateEthTokenPrice();
        // When the post2 method must be called, the corresponding NToken price is queried
        if (ok && minnerState.isMustPost2()) {
            result = erc20State.updateEthNtokenPrice();
        } else {
            result = ok;
        }
        return result;
    }

    /**
     * Send quotation: a single quotation
     *
     * @param wallet
     * @param gasPrice
     * @param nonce
     * @return
     */
    private String post(Wallet wallet, BigInteger gasPrice, BigInteger nonce) {
        Wallet.Asset offerNeed = wallet.getOfferNeed();
        // Quotation token address
        String address = erc20State.token.getAddress();
        // Quantity of quoted ETH (unit is ETH)
        BigInteger ethNum = nestProperties.getMiningEthUnit().toBigInteger();
        // Number of quoted tokens (in the smallest unit)
        BigInteger tokenAmount = offerNeed.getTokenAmount().divide(ethNum);
        // The number of ETH that needs to be typed into the contract
        BigInteger payableEthAmount = wallet.getPayableEthAmount();

        log.info("POST quoted ETH quantity：{}, quoted {} quantity：{}, enter the number of contract ETH：{}", ethNum, erc20State.token.getSymbol(), tokenAmount, payableEthAmount);
        log.info("Quote trading nonce：" + nonce);

        List<Type> typeList = Arrays.<Type>asList(
                new Address(address),
                new Uint256(ethNum),
                new Uint256(tokenAmount));

        String offerHash = ethClient.offer(POST, wallet, gasPrice, nonce, typeList, payableEthAmount);
        return offerHash;
    }

    /**
     * Send Quotation Transactions: Initiate two quotations simultaneously
     *
     * @param wallet
     * @param gasPrice
     * @param nonce
     * @return
     */
    private String post2(Wallet wallet, BigInteger gasPrice, BigInteger nonce) {
        Wallet.Asset offerNeed = wallet.getOfferNeed();
        // Quotation token address
        String address = erc20State.token.getAddress();
        // Quantity of quoted ETH (unit is ETH)
        BigInteger ethNum = nestProperties.getMiningEthUnit().toBigInteger();
        // Quote the number of tokens (in the smallest unit), i.e., how many tokens can be exchanged for 1 ETH
        BigInteger tokenAmount = offerNeed.getTokenAmount().divide(ethNum);
        // Quote the number of NTokens (in the smallest unit), i.e., how many NTokens can be exchanged for 1 ETH
        BigInteger nTokenAmount = offerNeed.getnTokenAmount().divide(ethNum);
        // The number of ETH that needs to be typed into the contract
        BigInteger payableEthAmount = wallet.getPayableEthAmount();

        log.info("Post2 quotes the number of ETH：{},{} quantity：{} ,{} quantity：{} ,enter the number of contract ETH：{}", ethNum,
                erc20State.token.getSymbol(), tokenAmount,
                erc20State.nToken.getSymbol(), nTokenAmount,
                payableEthAmount);
        log.info("Quote trading nonce：" + nonce);

        List<Type> typeList = Arrays.<Type>asList(
                new Address(address),
                new Uint256(ethNum),
                new Uint256(tokenAmount),
                new Uint256(nTokenAmount));

        String offerHash = ethClient.offer(POST2, wallet, gasPrice, nonce, typeList, payableEthAmount);
        return offerHash;
    }

    /**
     * @param wallet
     * @param offerGasPrice
     * @param nonce
     * @param miningBlockNumber
     */
    private void checkOfferTransaction(Wallet wallet, BigInteger offerGasPrice, BigInteger nonce, BigInteger miningBlockNumber) {
        try {
            while (true) {
                /**
                 *   Sleep for 2 seconds, in order to prevent frequent excessive requests to Infura, resulting in a rapid reach of the node limit access (if the Infura paid node visits more times, it is better to remove the sleep)
                 */
                Thread.sleep(2000);

                /**
                 *   If the block number of the last quote is changed, then either someone else has successfully packaged it or you have successfully packaged it yourself, then you initiate the cancellation of the transaction.
                 *   1. If someone else succeeds, then cancel the transaction to avoid the block spacing
                 *   2. If it succeeds, the transaction is null and there is no loss due to the same nonce
                 */
                BigInteger nowMiningBlockNumber = ethClient.checkLatestMining();
                if (nowMiningBlockNumber != null) {
                    if (nowMiningBlockNumber.compareTo(miningBlockNumber) > 0) {
                        //Determine if the nonce has changed
                        if (nonceIsChanged(nonce, wallet.getCredentials().getAddress())) {
                            return;
                        }
                        // Cancellation of the deal is doubled on the original offer
                        BigInteger cancelGasPrice = MathUtils.toDecimal(offerGasPrice).multiply(gasPriceState.getCancelOfferGasPriceMul()).toBigInteger();
                        String cancelTransaction = ethClient.cancelTransaction(wallet, nonce, cancelGasPrice);

                        if (StringUtils.isEmpty(cancelTransaction)) {
                            continue;
                        }
                        log.info("Cancel the transaction hash：", cancelTransaction);
                        return;
                    }
                }

                /**
                 *   If the nonce changes, then the deal has been packaged. Or it could be covered by other transactions
                 */
                if (nonceIsChanged(nonce, wallet.getCredentials().getAddress())) {
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Check for abnormal quote status：{}", e.getMessage());
        }

    }

    private boolean nonceIsChanged(BigInteger nonce, String address) {
        BigInteger nowNonce = ethClient.ethGetTransactionCount(address);

        System.out.println("The current nonce：" + nowNonce);

        if (nowNonce == null) return false;

        if (nowNonce.compareTo(nonce) > 0) {
            log.info("Nonce value change");
            return true;
        }
        return false;
    }

    /**
     * Check whether the current block matches the set block interval
     *
     * @param ethBlockNumber    The current block number
     * @param miningBlockNumber Latest quote block number
     * @return
     */
    private boolean meetBlockInterval(BigInteger ethBlockNumber, BigInteger miningBlockNumber) {
        BigInteger blockInterval = minnerState.getBlockInterval();
        // Current block interval
        BigInteger subtract = ethBlockNumber.subtract(miningBlockNumber);

        boolean b = false;
        log.info("Block height of last quote: {} Current block height: {} Block interval: {}", miningBlockNumber, ethBlockNumber, subtract);

        if (subtract.compareTo(blockInterval) >= 0) {
            b = true;
        } else {
            log.info("Current block interval: {}, does not meet the quotation interval condition", subtract);
        }
        return b;
    }

}
