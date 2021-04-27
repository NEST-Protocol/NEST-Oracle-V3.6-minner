package com.nest.ib.service;

import com.nest.ib.model.Wallet;


public interface MiningService {

    void offer(Wallet wallet);

    boolean updatePrice();

    void closePriceSheets(Wallet wallet);
}
