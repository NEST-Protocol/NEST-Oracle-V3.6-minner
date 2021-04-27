### NEST3.6自动报价程序操作说明

[toc]


#### 介绍
>NEST3.6自动报价程序是一个示例程序，可以根据本程序代码逻辑进行二次开发，开发中遇到任何问题，均可在github上提交问题，本程序开发人员会一一解答。

>本程序相关参数默认值如区块间隔、报价gasPrice倍数等并不是最优的策略方案，用户可根据实际情况进行调整。

>自动报价程序主要功能有：
   * 检查账户资产、解冻资产、冻结资产情况。
   * ERC20代币授权。
   * NTOKEN发行量检查，超过500万，将开启post2报价。
   * 交易所价格更新（部分地区交易所获取价格需要开启全局VPN）。
   * 发起报价（包含post、post2报价）。
   * 取消报价（自动判断合约最新报价区块号是否改变，如果最新区块号大于自己报价时区块号，则取消报价，避免被卡区块）。
   * 解冻报价资产，支持单个解冻和批量解冻。
   * 取出合约内解冻资产。
   * 重新获取合约参数。

#### 启动前准备

1. 准备好：钱包私钥及相关资产、以太坊节点URL、TOKEN代币合约地址、对应交易所交易对。
   * 钱包私钥：
   通过助记词生成，可通过nestDapp注册。
   * 需要资产
   NEST挖矿至少需要准备60.5ETH、价值30ETH的TOKEN代币和价值30ETH的NTOKEN代币以及20万NEST。
   ntoken挖矿post报价至少需要准备10.5ETH、价值10ETH的TOKEN代币和10万NEST，post2报价至少需要准备20.5ETH、价值20ETH的TOKEN代币和价值20ETH的NTOKEN代币以及20万NEST
   * 以太坊节点URL。
   * TOKEN代币合约地址：
   例如报价`ETH/USDT`，那么就需要填写USDT代币合约地址`0xdac17f958d2ee523a2206206994597c13d831ec7`。
   * 交易所交易对设置：
   <br/>火币交易对查询地址 https://api.huobi.pro/v1/common/symbols
   <br/>霍比特交易对查询地址 https://api.hbtc.com/openapi/v1/pairs
   <br/>抹茶交易对查询地址 https://www.mxc.com/open/api/v2/market/symbols

#### 启动和关闭

1. 运行报价程序：
   * 前往[releases](https://github.com/NEST-Protocol/NEST-Oracle-V3.6-minner/releases/tag/NEST-Oracle-V3.6-minner) 下载 NEST-oracle-V3.6-miner.zip 文件。
   * 双击报价程序根路径下start.bat，运行报价程序，会弹出窗口，请勿关闭，可在窗口查看报价、解冻资产等日志信息。
2. 登录：
   * 浏览器输入http://127.0.0.1:8088/main，会进入登录页面，默认用户名nest，密码nestqwe123!。
   * 如需修改密码，可修改src/main/resources/application.yml中的login.user.name（用户名）、login.user.passwd（密码）。
3. 关闭报价程序：
   * 关闭报价程序前先停止挖矿，然后等待10分钟，待报价资产确认解冻完毕后再关闭窗口。

#### 相关设置

1. 以太坊节点（必填）：
   * 必须优先设置节点地址。
2. 设置TOKEN地址、选择价格源、填写交易所对应的TOKEN交易对（必填）：
   * 如果是`ETH/USDT`报价，TOKEN地址填`0xdac17f958d2ee523a2206206994597c13d831ec7`，可以选择`Huobi Exchange`，交易对应填写火币交易对`ethusdt`。
   * 如果是`ETH/COFI`报价，TOKEN地址填`0x1a23a6BfBAdB59fa563008c0fB7cf96dfCF34Ea1`，可以选择`Mc Exchange`，交易对应填写抹茶交易对`COFI_USDT`。
3. 设置NTOKEN价格源、交易所NTOKEN对应的交易对（当NTOKEN发行量大于500万时必填）：
   * 根据当前NTOKEN的市场情况，目前已经上交易所的有NEST、NHBTC、NYFI，对此可以选择不同的交易所获取价格：
       * 如果是获取NEST价格，可以选择`Huobi Exchange`，交易对应填写火币交易对`nesteth`。
       * 如果是获取NHBTC价格，可以选择`Huobi Exchange`，交易对应填写火币交易对`nhbtceth`。
       * 如果是获取NYFI价格，可以选择`Hbtc Exchange`，交易对应填写霍比特交易对`NYFIUSDT`。
   * 其他NTOKEN可以选择`Fixed price`，交易对可以不填，但是必须在下方输入框填写固定价格，即1ETH等价多少个NTOKEN。
4. TOKEN、NTOKEN价格源和交易对确定后，点击`confirm`按钮，后台日志会打印TOKEN和NTOKEN的代币信息、交易所价格信息，检查信息无误后继续后续操作。
5. 设置报价私钥（必填）：
   * 填写私钥，程序会进行授权检查，如果未授权，程序会自动发起授权交易，请确定授权交易打包成功后方可进行报价。
   * 默认会对TOKEN进行授权，如果NTOKEN发行量大于500万，则同时会对NTOKEN进行授权。
6. gasPrice 倍数配置（在默认gasPrice基础上进行加倍，可根据实际情况进行调整）。
7. 开启挖矿：
   * 以上配置完成后，便可开启挖矿，可以在后台日志查看报价时区块高度、报价数量、报价交易hash等信息。


#### 测试报价

```
1. 将发起报价交易代码注释：
   // post报价
   List<Type> typeList = Arrays.<Type>asList(
                new Address(address),
                new Uint256(ethNum),
                new Uint256(tokenAmount));
   String offerHash = ethClient.offer(POST, wallet, gasPrice, nonce, typeList, payableEthAmount);

   // post2报价
   List<Type> typeList = Arrays.<Type>asList(
                new Address(address),
                new Uint256(ethNum),
                new Uint256(tokenAmount),
                new Uint256(nTokenAmount));

   String offerHash = ethClient.offer(POST2, wallet, gasPrice, nonce, typeList, payableEthAmount);

2. 打开 http://127.0.0.1:8088/main ,进行相关配置后，修改启动状态。

3. 查看后台窗口打印的报价ETH数量和报价TOKEN或NTOKEN数量，核对数据。
```

