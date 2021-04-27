package com.nest.ib.helper;

import com.nest.ib.contract.PriceSheetView;
import com.nest.ib.service.MiningService;
import com.nest.ib.utils.EthClient;
import com.nest.ib.config.NestProperties;
import com.nest.ib.constant.Constant;
import com.nest.ib.model.Wallet;
import com.nest.ib.state.Erc20State;
import com.nest.ib.state.MinnerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.web3j.tuples.generated.Tuple2;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * @author wll
 * @date 2020/8/24 9:47
 */
@Component
public class WalletHelper {

    private static final Logger log = LoggerFactory.getLogger(WalletHelper.class);
    private static Wallet WALLET;

    @Autowired
    private EthClient ethClient;
    @Autowired
    private NestProperties nestProperties;
    @Autowired
    private Erc20State erc20State;
    @Autowired
    private MinnerState minnerState;
    @Autowired
    private MiningService miningService;

    public static void updateWallet(Wallet wallet) {
        WALLET = wallet;
    }

    public static Wallet getWallet() {
        if (WALLET == null) {
            log.warn("The wallet is empty");
            return null;
        }

        return WALLET;
    }

    /**
     * Update wallet assets
     */
    public void updateWalletBalance() {
        if (WALLET != null) {
            if (!miningService.updatePrice()) return;
            updateBalance(WALLET, true);
        }
    }


    /**
     * Check the balance：
     * 1.ETH balance check: check whether the balance of CLOSE in the contract is sufficient.
     * 1.1 If it is sufficient, the balance of ETH+ CLOSE will be determined to be sufficient; if it is sufficient, the balance will be charged to the balance + commission
     * 1.2 can be a one-time into a number of quotation required ETH, such as into 600ETH, each quotation only into the handling fee can be
     * 2. Check token balance to determine whether the balance of CLOSE + account balance meets the quoted price
     * 3. If it is post2, check the NTOKEN balance
     * 4.NEST balance check to determine whether CLSOE's balance + account balance meets the quotation, and 100,000 NEST is required for each quotation pair. If it is Post2, two quotation pairs will be generated for each quotation
     *
     * @param sumTotal true Count all assets (account + unfrozen + frozen), false does not count all assets
     */
    public boolean updateBalance(Wallet wallet, boolean sumTotal) {

        // Default asset shortage
        wallet.setRich(false);
        boolean rich = true;
        String address = wallet.getCredentials().getAddress();
        // Get the ETH balance of the account
        BigInteger ethBalance = ethClient.ethGetBalance(address);
        if (ethBalance == null) {
            return false;
        }
        // Get the account token balance
        BigInteger tokenBalance = ethClient.ethBalanceOfErc20(address, erc20State.token.getAddress());
        if (tokenBalance == null) {
            return false;
        }
        // Get the NEST balance of your account
        BigInteger nestBalance = ethClient.ethBalanceOfErc20(address, nestProperties.getNestTokenAddress());
        if (nestBalance == null) {
            return false;
        }

        log.info("{} account balance：ETH={}，{}={}，nest={}", address, ethBalance, erc20State.token.getSymbol(), tokenBalance, nestBalance);
        Wallet.Asset account = wallet.getAccount();
        account.setEthAmount(ethBalance);
        account.setNestAmount(nestBalance);
        account.setTokenAmount(tokenBalance);

        // Get the token balances of CLOSE
        BigInteger closeToken = ethClient.balanceOfInContract(erc20State.token.getAddress(), address);

        Wallet.Asset closed = wallet.getClosed();
        closed.setEthAmount(BigInteger.ZERO);
        closed.setTokenAmount(closeToken);

        // Get the NEST balance of close, which contains the number of NEST mined, and can also be used for direct quotation
        BigInteger closeNest = ethClient.balanceOfInContract(nestProperties.getNestTokenAddress(), address);
        if (closeNest == null) return false;
        closed.setNestAmount(closeNest);

        log.info("{} Assets are unfrozen under the contract ，{}={}，nest={}", address, erc20State.token.getSymbol(), closeToken, closeNest);


        // Current Total Available Balance, Account Balance + Unfrozen Balance
        Wallet.Asset useable = wallet.getUseable();
        Wallet.Asset useableTemp = new Wallet.Asset();
        useableTemp.addAsset(closed);
        useableTemp.addAsset(account);
        useable.setAsset(useableTemp);

        // Quotation required assets
        Wallet.Asset offerNeed = wallet.getOfferNeed();

        // Get the Ntoken balance
        BigInteger nTokenBanlance = null, closeNtoken = null;
        // If USDT is quoted, NToken is NEST
        if (erc20State.token.getSymbol().equalsIgnoreCase("USDT")) {
            nTokenBanlance = nestBalance;
            closeNtoken = closeNest;
        } else {
            closeNtoken = ethClient.balanceOfInContract(erc20State.nToken.getAddress(), address);
            if (closeNtoken == null) return false;

            nTokenBanlance = ethClient.ethBalanceOfErc20(address, erc20State.nToken.getAddress());
            if (nTokenBanlance == null) {
                return false;
            }
        }
        closed.setnTokenAmount(closeNtoken);
        account.setnTokenAmount(nTokenBanlance);

        // Total number of available NTokens
        BigInteger usableNtoken = closeNtoken.add(nTokenBanlance);
        useable.setnTokenAmount(usableNtoken);

        // If it is post2, check whether the balance of NToken meets the quoted price, and double the ETH and Nest of the required quoted price, and the handling fee remains the same
        // The number of quotations is 1 by default and 2 by post2
        BigDecimal orderCount = BigDecimal.ONE;
        if (minnerState.isMustPost2()) {
            orderCount = Constant.BIG_DECIMAL_TWO;
            log.info("Total current available  {} balance ：{}", erc20State.nToken.getSymbol(), usableNtoken);

            // NToken requires quantity: base quote ETH scale * price
            if (erc20State.nToken.getEthErc20Price() == null) return false;
            BigDecimal multiplyNtoken = erc20State.nToken.getEthErc20Price().multiply(nestProperties.getMiningEthUnit());
            BigInteger needNtoken = multiplyNtoken.multiply(erc20State.nToken.getDecPowTen()).toBigInteger();

            offerNeed.setnTokenAmount(needNtoken);
            if (usableNtoken.compareTo(needNtoken) < 0) {
                log.warn("{} balance insufficient, need: {}, balance: {}", erc20State.nToken.getSymbol(), needNtoken, usableNtoken);
                rich = false;
            }
        }

        // Quantity required by ETH: base quotation size * quantity of quotation sheet + quotation fee (base quotation size * quotation fee ratio)
        BigInteger needEth = nestProperties.getMiningEthUnit().multiply(Constant.UNIT_DEC18).multiply(orderCount).toBigInteger();
        // Quotation commission
        BigInteger miningFee = nestProperties.getMiningFee().multiply(Constant.UNIT_DEC18).toBigInteger();
        needEth = needEth.add(miningFee);
        // Number of tokens required: base quote size * price
        if (erc20State.token.getEthErc20Price() == null) return false;
        BigDecimal multiplyToken = erc20State.token.getEthErc20Price().multiply(nestProperties.getMiningEthUnit());
        BigInteger needToken = multiplyToken.multiply(erc20State.token.getDecPowTen()).toBigInteger();
        // Nest Required Quantity: Number of mortgage assets per quotation * Number of quotations
        BigInteger needNest = nestProperties.getNestStakedNum().multiply(Constant.UNIT_INT18).multiply(orderCount.toBigInteger());

        offerNeed.setEthAmount(needEth);
        offerNeed.setNestAmount(needNest);
        offerNeed.setTokenAmount(needToken);

        BigInteger usableEth = useable.getEthAmount();
        if (usableEth.compareTo(needEth) < 0) {
            log.warn("Insufficient ETH balance, need: {}, available balance: {}", needEth, usableEth);
            rich = false;
        }

        BigInteger usableNest = useable.getNestAmount();
        // If it is a USDT offer, the NEST is the required NEST= the number of mortgages + the number of offers
        if (erc20State.token.getSymbol().equalsIgnoreCase("USDT")) {
            needNest = needNest.add(offerNeed.getnTokenAmount());
        }

        if (usableNest.compareTo(needNest) < 0) {
            log.warn("Nest balance insufficient, need: {}, available balance: {}", needNest, usableNest);
            rich = false;
        }

        BigInteger usableToken = useable.getTokenAmount();
        if (usableToken.compareTo(needToken) < 0) {
            log.warn("{} insufficient balance, need: {}, available balance: {}", erc20State.token.getSymbol(), needToken, usableToken);
            rich = false;
        }

        // Calculate total assets
        if (sumTotal) {
            Wallet.Asset freezedAssetTemp = new Wallet.Asset();

            // Token quotes freeze assets
            List<PriceSheetView> tokenPriceSheets = ethClient.unClosedSheetListOf(address, erc20State.token.getAddress(), minnerState.getMaxFindNum());
            addFreezedAsset(freezedAssetTemp, tokenPriceSheets, erc20State.token);

            // Post2 NToken quotes frozen assets
            if (minnerState.isMustPost2()) {
                List<PriceSheetView> nTokenPriceSheets = ethClient.unClosedSheetListOf(address, erc20State.nToken.getAddress(), minnerState.getMaxFindNum());
                addFreezedAsset(freezedAssetTemp, nTokenPriceSheets, erc20State.nToken);
            }

            wallet.getFreezed().setAsset(freezedAssetTemp);

            Wallet.Asset total = wallet.getTotal();
            Wallet.Asset totalTemp = new Wallet.Asset();
            totalTemp.addAsset(account);
            totalTemp.addAsset(wallet.getFreezed());
            totalTemp.addAsset(closed);
            total.setAsset(totalTemp);
        }

        wallet.setPayableEthAmount(needEth);
        wallet.setRich(rich);
        return rich;
    }

    private void addFreezedAsset(Wallet.Asset freezed, List<PriceSheetView> priceSheets, Erc20State.Item erc20) {
        if (!CollectionUtils.isEmpty(priceSheets)) {
            for (PriceSheetView priceSheet : priceSheets) {
                BigInteger nestNum1k = priceSheet.getNestNum1k();
                BigInteger nestNumBal = nestNum1k.multiply(Constant.BIG_INTEGER_1K);

                // Mortgage of the NEST
                BigInteger nestAmount = nestNumBal.multiply(Constant.UNIT_INT18);
                freezed.addNestAmount(nestAmount);

                // Ethnumbal is the amount of ETH left
                freezed.addEthAmount(priceSheet.getEthNumBal().multiply(Constant.UNIT_INT18));

                boolean usdtOffer = erc20State.token.getSymbol().equalsIgnoreCase("USDT");
                // TokenNumbal * TokenAmountPereth is the number of tokens/NTokens left
                if (erc20.getAddress().equalsIgnoreCase(erc20State.token.getAddress())) {
                    freezed.addTokenAmount(priceSheet.getTokenNumBal().multiply(priceSheet.price));
                    if (usdtOffer) {
                        freezed.addNtokenAmount(nestAmount);
                    }
                } else {
                    if (usdtOffer) {
                        // USDT quotation, frozen Ntoken is NEST, need to add the NEST of mortgage
                        BigInteger freezedNtoken = priceSheet.getTokenNumBal().multiply(priceSheet.price);
                        freezed.addNtokenAmount(freezedNtoken);
                        freezed.addNtokenAmount(nestAmount);
                        freezed.addNestAmount(freezedNtoken);
                    } else {
                        freezed.addNtokenAmount(priceSheet.getTokenNumBal().multiply(priceSheet.price));
                    }
                }

            }
        }
    }

}
