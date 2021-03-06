package com.nest.ib.model;

import com.nest.ib.constant.Constant;
import com.nest.ib.utils.MathUtils;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * @author wll
 * @date 2020/8/24 9:43
 */
public class Wallet {

    /**
     * True: Sufficient balance to judge whether quotation can be made
     */
    private volatile boolean rich = false;

    private volatile Credentials credentials;

    /**
     * Quotations should be made into ETH assets
     */
    private volatile BigInteger payableEthAmount;

    /**
     * Total wallet balance: account balance + unfrozen assets + frozen assets
     */
    private Asset total = new Asset();

    /**
     * The account balance
     */
    private Asset account = new Asset();

    /**
     * Unfrozen assets
     */
    private Asset closed = new Asset();

    /**
     * Frozen assets
     */
    private Asset freezed = new Asset();

    /**
     * Currently available assets: unfrozen assets + assets in the account
     */
    private Asset useable = new Asset();

    /**
     * Quotation required assets
     */
    private Asset offerNeed = new Asset();

    public void clearAsset() {
        total.clearAsset();
        account.clearAsset();
        closed.clearAsset();
        freezed.clearAsset();
        useable.clearAsset();
        offerNeed.clearAsset();
    }

    public static class Asset {
        /**
         * eth
         */
        private volatile BigInteger ethAmount;

        /**
         * token
         */
        private volatile BigInteger tokenAmount;

        /**
         * Ntoken
         */
        private volatile BigInteger nTokenAmount = BigInteger.ZERO;

        /**
         * nest
         */
        private volatile BigInteger nestAmount;

        public void addEthAmount(BigInteger ethAmount) {
            if (this.ethAmount == null) {
                this.ethAmount = BigInteger.ZERO;
            }
            if (ethAmount == null) return;

            this.ethAmount = this.ethAmount.add(ethAmount);
        }

        public void addTokenAmount(BigInteger tokenAmount) {
            if (this.tokenAmount == null) {
                this.tokenAmount = BigInteger.ZERO;
            }
            if (tokenAmount == null) return;

            this.tokenAmount = this.tokenAmount.add(tokenAmount);
        }

        public void addNtokenAmount(BigInteger nTokenAmount) {
            if (this.nTokenAmount == null) {
                this.nTokenAmount = BigInteger.ZERO;
            }
            if (nTokenAmount == null) return;

            this.nTokenAmount = this.nTokenAmount.add(nTokenAmount);
        }

        public void addNestAmount(BigInteger nestAmount) {
            if (this.nestAmount == null) {
                this.nestAmount = BigInteger.ZERO;
            }
            if (nestAmount == null) return;

            this.nestAmount = this.nestAmount.add(nestAmount);
        }

        public boolean isNull(boolean mustPost2) {
            if (mustPost2 && nTokenAmount == null) {
                return true;
            }

            if (ethAmount == null
                    || tokenAmount == null
                    || nestAmount == null) {
                return true;
            }
            return false;
        }

        public BigInteger getEthAmount() {
            return ethAmount;
        }

        public BigDecimal getEthAmountUnit() {
            if (ethAmount == null) return BigDecimal.ZERO;
            return MathUtils.toDecimal(ethAmount).divide(Constant.UNIT_ETH, 4, BigDecimal.ROUND_DOWN);
        }

        public void setEthAmount(BigInteger ethAmount) {
            this.ethAmount = ethAmount;
        }

        public BigInteger getTokenAmount() {
            return tokenAmount;
        }

        public BigDecimal getTokenAmountUnit(BigDecimal unit) {
            if (tokenAmount == null || unit == null || unit.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
            return MathUtils.toDecimal(tokenAmount).divide(unit, 10, BigDecimal.ROUND_DOWN);
        }

        public void setTokenAmount(BigInteger tokenAmount) {
            this.tokenAmount = tokenAmount;
        }

        public BigInteger getnTokenAmount() {
            return nTokenAmount;
        }

        public BigDecimal getNtokenAmountUnit(BigDecimal unit) {
            if (nTokenAmount == null || unit == null || unit.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
            return MathUtils.toDecimal(nTokenAmount).divide(unit, 10, BigDecimal.ROUND_DOWN);
        }

        public void setnTokenAmount(BigInteger nTokenAmount) {
            this.nTokenAmount = nTokenAmount;
        }

        public BigInteger getNestAmount() {
            return nestAmount;
        }

        public BigDecimal getNestAmountUnit() {
            if (nestAmount == null) return BigDecimal.ZERO;
            return MathUtils.toDecimal(nestAmount).divide(Constant.UNIT_DEC18, 10, BigDecimal.ROUND_DOWN);
        }

        public void setNestAmount(BigInteger nestAmount) {
            this.nestAmount = nestAmount;
        }

        public void addAsset(Asset asset) {
            this.addEthAmount(asset.ethAmount);
            this.addTokenAmount(asset.tokenAmount);
            this.addNtokenAmount(asset.nTokenAmount);
            this.addNestAmount(asset.nestAmount);
        }

        public void clearAsset() {
            this.ethAmount = null;
            this.tokenAmount = null;
            this.nTokenAmount = null;
            this.nestAmount = null;
        }

        public void setAsset(Asset asset) {
            this.ethAmount = asset.ethAmount;
            this.tokenAmount = asset.tokenAmount;
            this.nTokenAmount = asset.nTokenAmount;
            this.nestAmount = asset.nestAmount;
        }
    }

    public BigInteger getPayableEthAmount() {
        return payableEthAmount;
    }

    public void setPayableEthAmount(BigInteger payableEthAmount) {
        this.payableEthAmount = payableEthAmount;
    }

    public boolean isRich() {
        return rich;
    }

    public void setRich(boolean rich) {
        this.rich = rich;
    }

    public Asset getOfferNeed() {
        return offerNeed;
    }

    public void setOfferNeed(Asset offerNeed) {
        this.offerNeed = offerNeed;
    }

    public Asset getAccount() {
        return account;
    }

    public void setAccount(Asset account) {
        this.account = account;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public Asset getTotal() {
        return total;
    }

    public void setTotal(Asset total) {
        this.total = total;
    }

    public Asset getClosed() {
        return closed;
    }

    public void setClosed(Asset closed) {
        this.closed = closed;
    }

    public Asset getFreezed() {
        return freezed;
    }

    public void setFreezed(Asset freezed) {
        this.freezed = freezed;
    }

    public Asset getUseable() {
        return useable;
    }

    public void setUseable(Asset useable) {
        this.useable = useable;
    }
}
