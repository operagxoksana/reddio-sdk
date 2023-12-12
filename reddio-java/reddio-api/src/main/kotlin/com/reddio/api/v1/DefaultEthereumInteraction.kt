package com.reddio.api.v1

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.reddio.abi.Deposits
import com.reddio.abi.ReddioDeployHelper
import com.reddio.abi.ReddioDeployHelperAsset
import com.reddio.abi.Withdrawals
import com.reddio.abi.getlog.EthGetLogsForDeposits
import com.reddio.api.v1.rest.GetAssetIdMessage
import com.reddio.api.v1.rest.GetContractInfoMessage
import com.reddio.api.v1.rest.GetVaultIdMessage
import com.reddio.api.v1.rest.ReddioRestClient
import com.reddio.contract.EthNextEventSubscriber
import com.reddio.crypto.CryptoService
import com.reddio.exception.ReddioException
import com.reddio.gas.GasOption
import com.reddio.gas.StaticGasLimitSuggestionPriceGasProvider
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.web3j.contracts.eip20.generated.ERC20
import org.web3j.contracts.eip721.generated.ERC721
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.Web3jService
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.tuples.generated.Tuple2
import org.web3j.tx.gas.ContractGasProvider
import org.web3j.utils.Convert
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer


class DefaultEthereumInteraction(
    private val restClient: ReddioRestClient,
    private val chainId: Long,
    private val web3jService: Web3jService,
    private val credentials: Credentials,
) : EthereumInteraction {

    private val web3j = Web3j.build(web3jService);
    private val quantizedHelper = QuantizedHelper(restClient)

    // TODO: optimize it for concurrent access
    private val subscriptions = mutableMapOf<UUID, Disposable>();

    private val closed = AtomicBoolean(false);

    override fun depositETH(
        starkKey: String,
        amount: String,
        gasOption: GasOption,
    ): CompletableFuture<LogDeposit> {
        return this.depositETH(
            starkKey, amount, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun depositETH(
        starkKey: String,
        amount: String,
        gasOption: GasOption,
        gasLimit: BigInteger,
    ): CompletableFuture<LogDeposit> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                asyncDepositETH(starkKey, amount, gasProvider)
            }
        }
    }


    override fun depositERC20(
        tokenAddress: String, starkKey: String, amount: String, gasOption: GasOption
    ): CompletableFuture<LogDeposit> {
        return this.depositERC20(
            tokenAddress, starkKey, amount, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun depositERC20(
        tokenAddress: String, starkKey: String, amount: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<LogDeposit> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                asyncERC20Approve(tokenAddress, amount, web3j, gasProvider)
                asyncDepositERC20(tokenAddress, starkKey, amount, gasProvider)
            }
        }
    }

    private suspend fun asyncERC20Approve(
        erc20ContractAddress: String,
        amount: String,
        web3j: Web3j,
        gasProvider: ContractGasProvider,
    ): ERC20.ApprovalEventResponse {
        val erc20Contract = ERC20.load(erc20ContractAddress, web3j, credentials, gasProvider)
        val decimals = erc20Contract.decimals().send()
        val amountAfterDecimals = amount.toBigDecimal().movePointRight(decimals.toInt())
        val call = erc20Contract.approve(
            this.reddioStarexContractAddress(),
            amountAfterDecimals.toBigInteger(),
        )
        call.send()
        return EthNextEventSubscriber.create(erc20Contract::approvalEventFlowable, web3j).subscribeNextEvent()
    }

    private suspend fun asyncERC721Approve(
        erc721ContractAddress: String,
        gasProvider: ContractGasProvider,
        tokenId: String,
    ): ERC721.ApprovalEventResponse {
        val erc721Contract = ERC721.load(erc721ContractAddress, web3j, credentials, gasProvider)
        val call = erc721Contract.approve(
            this.reddioStarexContractAddress(),
            BigInteger(tokenId, 10),
            BigInteger.ZERO,
        )
        call.send()
        return EthNextEventSubscriber.create(erc721Contract::approvalEventFlowable, web3j).subscribeNextEvent()
    }

    override fun depositERC721(
        tokenAddress: String, tokenId: String, starkKey: String, gasOption: GasOption
    ): CompletableFuture<LogDepositWithToken> {
        return this.depositERC721(
            tokenAddress, tokenId, starkKey, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun depositERC721(
        tokenAddress: String, tokenId: String, starkKey: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<LogDepositWithToken> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                asyncERC721Approve(tokenAddress, gasProvider, tokenId);
                asyncDepositERC721(tokenAddress, tokenId, starkKey, gasProvider)
            }
        }
    }

    internal suspend fun asyncDepositETH(
        starkKey: String, amount: String, gasProvider: ContractGasProvider
    ): LogDeposit {
        val (assetId, assetType) = getAssetTypeAndId("ETH", "ETH", "")
        val vaultId =
            restClient.getVaultId(GetVaultIdMessage.of(assetId, listOf(starkKey))).await().getData().vaultIds[0]
        val deposits = Deposits.load(
            this.reddioStarexContractAddress(), web3j, credentials, gasProvider
        )
        val amountInWei = Convert.toWei(amount, Convert.Unit.ETHER)
        val call = deposits.depositEth(
            BigInteger(starkKey.toLowerCase().replace("0x", ""), 16),
            BigInteger(assetType.toLowerCase().replace("0x", ""), 16),
            BigInteger(vaultId, 10),
            amountInWei.toBigInteger(),
        );

        call.send();
        val event = EthNextEventSubscriber.create(deposits::logDepositEventFlowable, web3j).subscribeNextEvent()
        return LogDeposit.of(
            event.depositorEthKey,
            event.starkKey.toString(16),
            event.vaultId.toString(10),
            event.assetType.toString(16),
            event.nonQuantizedAmount.toString(10),
            event.quantizedAmount.toString(10)
        )
    }

    internal suspend fun asyncDepositERC20(
        tokenAddress: String, starkKey: String, amount: String, gasProvider: ContractGasProvider
    ): LogDeposit {
        val (assetId, assetType) = getAssetTypeAndId("ERC20", tokenAddress, "")

        val quantizedAmount = quantizedHelper.quantizedAmount(amount, "ERC20", tokenAddress)
        val vaultId =
            restClient.getVaultId(GetVaultIdMessage.of(assetId, listOf(starkKey))).await().getData().vaultIds[0]
        val deposits = Deposits.load(
            this.reddioStarexContractAddress(), web3j, credentials, gasProvider
        )

        val call = deposits.depositERC20(
            BigInteger(starkKey.toLowerCase().replace("0x", ""), 16),
            BigInteger(assetType.toLowerCase().replace("0x", ""), 16),
            BigInteger(vaultId, 10),
            quantizedAmount.toBigInteger(),
        )
        call.send()
        val event = EthNextEventSubscriber.create(deposits::logDepositEventFlowable, web3j).subscribeNextEvent()
        return LogDeposit.of(
            event.depositorEthKey,
            event.starkKey.toString(16),
            event.vaultId.toString(10),
            event.assetType.toString(16),
            event.nonQuantizedAmount.toString(10),
            event.quantizedAmount.toString(10)
        )
    }

    internal suspend fun asyncDepositERC721(
        tokenAddress: String,
        tokenId: String,
        starkKey: String,
        gasProvider: ContractGasProvider,
    ): LogDepositWithToken {
        val (assetId, assetType) = getAssetTypeAndId("ERC721", tokenAddress, tokenId)

        val vaultId =
            restClient.getVaultId(GetVaultIdMessage.of(assetId, listOf(starkKey))).await().getData().vaultIds[0]

        val deposits = Deposits.load(
            this.reddioStarexContractAddress(), web3j, credentials, gasProvider
        )
        val call = deposits.depositNft(
            BigInteger(starkKey.toLowerCase().replace("0x", ""), 16),
            BigInteger(assetType.toLowerCase().replace("0x", ""), 16),
            BigInteger(vaultId, 10),
            BigInteger(tokenId, 10),
        )
        call.send()
        val event =
            EthNextEventSubscriber.create(deposits::logDepositWithTokenIdEventFlowable, web3j).subscribeNextEvent()
        return LogDepositWithToken.of(
            event.depositorEthKey,
            event.starkKey.toString(16),
            event.vaultId.toString(10),
            event.assetType.toString(16),
            event.tokenId.toString(10),
            event.assetId.toString(16),
            event.nonQuantizedAmount.toString(10),
            event.quantizedAmount.toString(10)
        )
    }


    override fun withdrawETHOrERC20(
        ethAddress: String, assetType: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.withdrawETHOrERC20(
            ethAddress, assetType, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun withdrawETHOrERC20(
        ethAddress: String, assetType: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                asyncWithdrawal(ethAddress, assetType, gasProvider)
            }
        }
    }


    override fun withdrawalETH(
        ethAddress: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.withdrawalETH(
            ethAddress, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun withdrawalETH(
        ethAddress: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val assetType = restClient.getContractInfo(
                    GetContractInfoMessage.of(
                        ReddioClient.TOKEN_TYPE_ETH, "ETH"
                    )
                ).await().data.getAssetType()
                asyncWithdrawal(ethAddress, assetType, gasProvider)
            }
        }
    }

    override fun withdrawalERC20(
        ethAddress: String, contractAddress: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.withdrawalERC20(
            ethAddress, contractAddress, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun withdrawalERC20(
        ethAddress: String, contractAddress: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val assetType = restClient.getContractInfo(
                    GetContractInfoMessage.of(
                        ReddioClient.TOKEN_TYPE_ERC20, contractAddress
                    )
                ).await().data.getAssetType()
                asyncWithdrawal(ethAddress, assetType, gasProvider)
            }
        }
    }

    override fun withdrawalERC721(
        ethAddress: String, contractAddress: String, tokenId: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.withdrawalERC721(
            ethAddress, contractAddress, tokenId, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun withdrawalERC721(
        ethAddress: String, contractAddress: String, tokenId: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val assetType = restClient.getContractInfo(
                    GetContractInfoMessage.of(
                        ReddioClient.TOKEN_TYPE_ERC721, contractAddress
                    )
                ).await().data.getAssetType()
                asyncWithdrawalERC721(ethAddress, assetType, tokenId, gasProvider)
            }
        }
    }

    override fun withdrawalERC721M(
        ethAddress: String, contractAddress: String, tokenId: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.withdrawalERC721M(
            ethAddress, contractAddress, tokenId, gasOption, StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun withdrawalERC721M(
        ethAddress: String, contractAddress: String, tokenId: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val assetType = restClient.getContractInfo(
                    GetContractInfoMessage.of(
                        ReddioClient.TOKEN_TYPE_ERC721M, contractAddress
                    )
                ).await().data.getAssetType()
                asyncWithdrawalERC721M(ethAddress, assetType, tokenId, gasProvider)
            }
        }
    }

    internal suspend fun asyncWithdrawal(
        ethAddress: String,
        assetType: String,
        gasProvider: ContractGasProvider,
    ): TransactionReceipt {
        val withdrawals = Withdrawals.load(this.reddioStarexContractAddress(), web3j, credentials, gasProvider)
        return withdrawals.withdraw(
            BigInteger(ethAddress.toLowerCase().replace("0x", ""), 16),
            BigInteger(assetType.toLowerCase().replace("0x", ""), 16),
        ).sendAsync().await()
    }

    internal suspend fun asyncWithdrawalERC721(
        ethAddress: String,
        assetType: String,
        tokenId: String,
        gasProvider: ContractGasProvider,
    ): TransactionReceipt {
        val withdrawals = Withdrawals.load(this.reddioStarexContractAddress(), web3j, credentials, gasProvider)
        return withdrawals.withdrawNft(
            BigInteger(ethAddress.toLowerCase().replace("0x", ""), 16),
            BigInteger(assetType.toLowerCase().replace("0x", ""), 16),
            BigInteger(tokenId, 10),
        ).sendAsync().await()
    }

    internal suspend fun asyncWithdrawalERC721M(
        ethAddress: String,
        assetType: String,
        tokenId: String,
        gasProvider: ContractGasProvider,
    ): TransactionReceipt {
        val withdrawals = Withdrawals.load(this.reddioStarexContractAddress(), web3j, credentials, gasProvider)

        return withdrawals.withdrawAndMint(
            BigInteger(ethAddress.toLowerCase().replace("0x", ""), 16),
            BigInteger(assetType.toLowerCase().replace("0x", ""), 16),
            Numeric.hexStringToByteArray(BigInteger(tokenId, 10).toString(16)),
        ).sendAsync().await()
    }

    private suspend fun getAssetTypeAndId(
        type: String,
        tokenAddress: String,
        tokenId: String,
    ): AssetIdAndAssetType {
        val contractInfo = restClient.getContractInfo(GetContractInfoMessage.of(type, tokenAddress)).await().getData()
        val result =
            restClient.getAssetId(GetAssetIdMessage.of(tokenAddress, type, tokenId, contractInfo.quantum)).await()
        return AssetIdAndAssetType(result.getData().getAssetId(), contractInfo.getAssetType())
    }

    private suspend fun reddioStarexContractAddress(): String {
        val starexContractsResponseResponseWrapper = this.restClient.starexContracts().await()
        if (this.chainId == MAINNET_ID) {
            return starexContractsResponseResponseWrapper.data.mainnet
        }
        return starexContractsResponseResponseWrapper.data.testnet
    }

    override fun watchDeposit(consumer: Consumer<Tuple2<Deposits.LogDepositEventResponse, EthBlock>>): Disposable {
        val currentBlockNumber = this.web3j.ethBlockNumber().send().blockNumber
        val startBlockNumber =
            currentBlockNumber.subtract(BigInteger.valueOf(BlockConfirmationRequiredEvents.DEFAULT_BLOCK_CONFIRMATION))
        return watchDeposit(consumer, startBlockNumber)
    }

    override fun watchDeposit(
        consumer: Consumer<Tuple2<Deposits.LogDepositEventResponse, EthBlock>>, startBlockNumber: BigInteger
    ): Disposable {
        return watchDeposit(consumer, startBlockNumber, BlockConfirmationRequiredEvents.DEFAULT_BLOCK_CONFIRMATION)
    }

    override fun watchDeposit(
        consumer: Consumer<Tuple2<Deposits.LogDepositEventResponse, EthBlock>>,
        startBlockNumber: BigInteger,
        requiredBlockConfirmation: Long
    ): Disposable {
        requireNotClosed()
        return runBlocking {
            val reddioStarexContractAddress = reddioStarexContractAddress()
            val blockConfirmationRequiredEvents = BlockConfirmationRequiredEvents(
                { startBlock, endBlock ->
                    EthGetLogsForDeposits.asFlowable(
                        EthGetLogsForDeposits.getLogsForLogDepositEvent(
                            web3j, startBlock, endBlock, reddioStarexContractAddress
                        )
                    )
                }, requiredBlockConfirmation, web3j
            )

            var uuid: UUID? = null

            val result = blockConfirmationRequiredEvents.eventFlowableWithEthBlock(startBlockNumber).subscribe({
                consumer.accept(it)
            }, {
                uuid?.let { uuid ->
                    cancelSubscription(uuid)
                }
                throw it
            }, {
                uuid?.let { uuid ->
                    cancelSubscription(uuid)
                }
            })
            uuid = registerSubscription(result)

            result
        }
    }


    override fun watchNftDeposit(consumer: Consumer<Tuple2<Deposits.LogNftDepositEventResponse, EthBlock>>): Disposable {
        val currentBlockNumber = this.web3j.ethBlockNumber().send().blockNumber
        val startBlockNumber =
            currentBlockNumber.subtract(BigInteger.valueOf(BlockConfirmationRequiredEvents.DEFAULT_BLOCK_CONFIRMATION))
        return watchNftDeposit(consumer, startBlockNumber)
    }

    override fun watchNftDeposit(
        consumer: Consumer<Tuple2<Deposits.LogNftDepositEventResponse, EthBlock>>, startBlockNumber: BigInteger
    ): Disposable {
        return watchNftDeposit(consumer, startBlockNumber, BlockConfirmationRequiredEvents.DEFAULT_BLOCK_CONFIRMATION)
    }

    override fun watchNftDeposit(
        consumer: Consumer<Tuple2<Deposits.LogNftDepositEventResponse, EthBlock>>,
        startBlockNumber: BigInteger,
        requiredBlockConfirmation: Long
    ): Disposable {
        requireNotClosed()
        return runBlocking {
            val reddioStarexContractAddress = reddioStarexContractAddress()
            val blockConfirmationRequiredEvents = BlockConfirmationRequiredEvents(
                { startBlock, endBlock ->
                    EthGetLogsForDeposits.asFlowable(
                        EthGetLogsForDeposits.getLogsForLogNftDepositEvent(
                            web3j, startBlock, endBlock, reddioStarexContractAddress
                        )
                    )
                }, requiredBlockConfirmation, web3j
            )

            var uuid: UUID? = null

            val result = blockConfirmationRequiredEvents.eventFlowableWithEthBlock(startBlockNumber).subscribe({
                consumer.accept(it)
            }, {
                uuid?.let { uuid ->
                    cancelSubscription(uuid)
                }
                throw it
            }, {
                uuid?.let { uuid ->
                    cancelSubscription(uuid)
                }
            })
            uuid = registerSubscription(result)

            result
        }
    }

    override fun getStarkPrivateKey(): BigInteger {
        val signature = ethSignReddioTypedPayload(SIGN_MESSAGE, this.chainId, this.credentials)
        return CryptoService.getPrivateKeyFromEthSignature(
            BigInteger(
                signature.replace("0x", "").toLowerCase(
                    Locale.getDefault()
                ), 16
            )
        )
    }

    override fun deployERC20AndRegister(
        reddioDeployHelperAddress: String, name: String, symbol: String, amount: BigInteger, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.deployERC20AndRegister(
            reddioDeployHelperAddress,
            name,
            symbol,
            amount,
            gasOption,
            StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun deployERC20AndRegister(
        reddioDeployHelperAddress: String,
        name: String,
        symbol: String,
        amount: BigInteger,
        gasOption: GasOption,
        gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val helper = ReddioDeployHelper.load(reddioDeployHelperAddress, web3j, credentials, gasProvider)
                helper.deployERC20AndRegister(
                    name, symbol, amount
                ).sendAsync().await()
            }
        }
    }


    override fun deployERC721AndRegister(
        reddioDeployHelperAddress: String, name: String, symbol: String, baseURI: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.deployERC721AndRegister(
            reddioDeployHelperAddress,
            name,
            symbol,
            baseURI,
            gasOption,
            StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun deployERC721AndRegister(
        reddioDeployHelperAddress: String,
        name: String,
        symbol: String,
        baseURI: String,
        gasOption: GasOption,
        gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val helper = ReddioDeployHelper.load(reddioDeployHelperAddress, web3j, credentials, gasProvider)
                helper.deployERC721AndRegister(
                    name, symbol, baseURI, ReddioDeployHelperAsset.ERC721.value.toBigInteger()
                ).sendAsync().await()
            }
        }
    }

    override fun deployERC721MAndRegister(
        reddioDeployHelperAddress: String, name: String, symbol: String, baseURI: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.deployERC721MAndRegister(
            reddioDeployHelperAddress,
            name,
            symbol,
            baseURI,
            gasOption,
            StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun deployERC721MAndRegister(
        reddioDeployHelperAddress: String,
        name: String,
        symbol: String,
        baseURI: String,
        gasOption: GasOption,
        gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val helper = ReddioDeployHelper.load(reddioDeployHelperAddress, web3j, credentials, gasProvider)
                helper.deployERC721AndRegister(
                    name, symbol, baseURI, ReddioDeployHelperAsset.ERC721Mintable.value.toBigInteger()
                ).sendAsync().await()
            }
        }
    }


    override fun deployERC721MCAndRegister(
        reddioDeployHelperAddress: String, name: String, symbol: String, gasOption: GasOption
    ): CompletableFuture<TransactionReceipt> {
        return this.deployERC721MCAndRegister(
            reddioDeployHelperAddress,
            name,
            symbol,
            gasOption,
            StaticGasLimitSuggestionPriceGasProvider.DEFAULT_GAS_LIMIT
        )
    }

    override fun deployERC721MCAndRegister(
        reddioDeployHelperAddress: String, name: String, symbol: String, gasOption: GasOption, gasLimit: BigInteger
    ): CompletableFuture<TransactionReceipt> {
        val gasProvider = StaticGasLimitSuggestionPriceGasProvider(
            this.chainId, gasOption, gasLimit
        )
        return CompletableFuture.supplyAsync {
            runBlocking {
                val helper = ReddioDeployHelper.load(reddioDeployHelperAddress, web3j, credentials, gasProvider)
                helper.deployERC721AndRegister(
                    name, symbol, "", ReddioDeployHelperAsset.ERC721MintableCustomURI.value.toBigInteger()
                ).sendAsync().await()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.closed.set(true)
        this.cancelAllSubscriptions()
    }

    @Synchronized
    private fun registerSubscription(subscription: Disposable): UUID {
        val uuid = UUID.randomUUID()
        this.subscriptions[uuid] = (subscription);
        return uuid
    }

    @Synchronized
    private fun cancelSubscription(uuid: UUID) {
        this.subscriptions[uuid]?.dispose()
        this.subscriptions.remove(uuid)
    }

    @Synchronized
    private fun cancelAllSubscriptions() {
        this.subscriptions.forEach { (_, value) -> value.dispose() }
        this.subscriptions.clear()
    }

    @Synchronized
    private fun requireNotClosed() {
        if (this.closed.get()) {
            throw ReddioException("Reddio DefaultEthereumInteraction is closed")
        }
    }

    companion object {

        @Deprecated("Use REDDIO_DEPLOY_HELPER_ADDRESS_SEPOLIA")
        const val REDDIO_DEPLOY_HELPER_ADDRESS_GOERLI = "0xE6f26945d915A9C6d5ebaF26a0dcD99caE74347d"
        const val REDDIO_DEPLOY_HELPER_ADDRESS_SEPOLIA = "0xE6f26945d915A9C6d5ebaF26a0dcD99caE74347d"

        private const val SIGN_MESSAGE = "Generate layer 2 key"
        private val objectMapper = ObjectMapper()

        class ReddioSignPayload {
            private constructor(
                domain: ReddioSignPayloadDomain,
                message: ReddioSignPayloadMessage,
                primaryType: String,
                types: Map<String, List<ReddioSignPayloadTypesEntry>>
            ) {
                this.domain = domain
                this.message = message
                this.primaryType = primaryType
                this.types = types
            }

            @JsonProperty("domain")
            val domain: ReddioSignPayloadDomain;

            @JsonProperty("message")
            val message: ReddioSignPayloadMessage;

            @JsonProperty("primaryType")
            val primaryType: String;

            @JsonProperty("types")
            val types: Map<String, List<ReddioSignPayloadTypesEntry>>

            companion object {
                fun create(
                    message: String, chainId: Long
                ): ReddioSignPayload {
                    return ReddioSignPayload(
                        ReddioSignPayloadDomain(chainId), ReddioSignPayloadMessage(message), "reddio", mapOf(
                            "EIP712Domain" to listOf(
                                ReddioSignPayloadTypesEntry("chainId", "uint256")
                            ), "reddio" to listOf(
                                ReddioSignPayloadTypesEntry("contents", "string")
                            )
                        )
                    )
                }
            }
        }

        class ReddioSignPayloadDomain(
            @JsonProperty("chainId") val chainId: Long
        )

        class ReddioSignPayloadMessage(
            @JsonProperty("contents") val contents: String
        )

        class ReddioSignPayloadTypesEntry(
            @JsonProperty("name") val name: String,

            @JsonProperty("type") val type: String
        )


        const val MAINNET_ID = 1L;

        @Deprecated("Use SEPOLIA_ID")
        const val GOERIL_ID = 5L;
        const val SEPOLIA_ID = 11155111L;

        @JvmStatic
        fun build(
            restClient: ReddioRestClient,
            chainId: Long,
            web3jService: Web3jService,
            credentials: Credentials,
        ): DefaultEthereumInteraction {
            return DefaultEthereumInteraction(restClient, chainId, web3jService, credentials)
        }

        @JvmStatic
        fun build(
            restClient: ReddioRestClient,
            chainId: Long,
            ethJSONRpcHTTPEndpoint: String,
            credentials: Credentials,
        ): DefaultEthereumInteraction {
            return DefaultEthereumInteraction(
                restClient, chainId, HttpService(ethJSONRpcHTTPEndpoint), credentials
            )
        }

        @JvmStatic
        fun build(
            restClient: ReddioRestClient,
            chainId: Long,
            ethJSONRpcHTTPEndpoint: String,
            ethPrivateKey: String,
        ): DefaultEthereumInteraction {
            return DefaultEthereumInteraction(
                restClient, chainId, HttpService(ethJSONRpcHTTPEndpoint), Credentials.create(ethPrivateKey)
            )
        }


        @JvmStatic
        fun getStarkPrivateKey(ethPrivateKey: String, chainId: Long): BigInteger {
            val credentials = Credentials.create(ethPrivateKey)
            val signature = ethSignReddioTypedPayload(SIGN_MESSAGE, chainId, credentials)
            return CryptoService.getPrivateKeyFromEthSignature(
                BigInteger(
                    signature.replace("0x", "").toLowerCase(
                        Locale.getDefault()
                    ), 16
                )
            )
        }

        @JvmStatic
        fun getStarkKeys(ethPrivateKey: String, chainId: Long): StarkKeys {
            val starkPrivateKey = getStarkPrivateKey(ethPrivateKey, chainId)
            val starkKey = CryptoService.getPublicKey(starkPrivateKey)
            return StarkKeys.of(
                "0x" + starkKey.toString(16), "0x" + starkPrivateKey.toString(16)
            )
        }

        private fun ethSignReddioTypedPayload(message: String, chainId: Long, credentials: Credentials): String {
            val hash = StructuredDataEncoder(
                objectMapper.writeValueAsString(
                    ReddioSignPayload.create(message, chainId)
                )
            ).hashStructuredData()
            val signature = Sign.signMessage(hash, credentials.ecKeyPair, false)
            val signatureBytes = ByteArray(65)
            System.arraycopy(signature.r, 0, signatureBytes, 0, 32)
            System.arraycopy(signature.s, 0, signatureBytes, 32, 32)
            System.arraycopy(signature.v, 0, signatureBytes, 64, 1)
            return Numeric.toHexString(signatureBytes)
        }
    }
}
