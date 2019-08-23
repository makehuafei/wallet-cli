package org.tron.walletcli;

import com.google.protobuf.ByteString;
import io.netty.util.internal.StringUtil;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.AddressPrKeyPairMessage;
import org.tron.api.GrpcAPI.AssetIssueList;
import org.tron.api.GrpcAPI.BlockExtention;
import org.tron.api.GrpcAPI.BytesMessage;
import org.tron.api.GrpcAPI.DecryptNotes;
import org.tron.api.GrpcAPI.DecryptNotes.NoteTx;
import org.tron.api.GrpcAPI.DecryptNotesMarked;
import org.tron.api.GrpcAPI.DiversifierMessage;
import org.tron.api.GrpcAPI.ExchangeList;
import org.tron.api.GrpcAPI.ExpandedSpendingKeyMessage;
import org.tron.api.GrpcAPI.IncomingViewingKeyDiversifierMessage;
import org.tron.api.GrpcAPI.IncomingViewingKeyMessage;
import org.tron.api.GrpcAPI.IvkDecryptAndMarkParameters;
import org.tron.api.GrpcAPI.IvkDecryptParameters;
import org.tron.api.GrpcAPI.NfParameters;
import org.tron.api.GrpcAPI.NodeList;
import org.tron.api.GrpcAPI.Note;
import org.tron.api.GrpcAPI.OvkDecryptParameters;
import org.tron.api.GrpcAPI.PaymentAddressMessage;
import org.tron.api.GrpcAPI.PrivateParameters;
import org.tron.api.GrpcAPI.PrivateParametersWithoutAsk;
import org.tron.api.GrpcAPI.ProposalList;
import org.tron.api.GrpcAPI.ReceiveNote;
import org.tron.api.GrpcAPI.SpendNote;
import org.tron.api.GrpcAPI.ViewingKeyMessage;
import org.tron.api.GrpcAPI.WitnessList;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Utils;
import org.tron.core.exception.CancelException;
import org.tron.core.exception.CipherException;
import org.tron.core.exception.ZksnarkException;
import org.tron.core.zen.ShieldedAddressInfo;
import org.tron.core.zen.ShieldedNoteInfo;
import org.tron.core.zen.ShieldedWrapper;
import org.tron.core.zen.ZenUtils;
import org.tron.core.zen.address.DiversifierT;
import org.tron.core.zen.address.ExpandedSpendingKey;
import org.tron.core.zen.address.FullViewingKey;
import org.tron.core.zen.address.SpendingKey;
import org.tron.keystore.StringUtils;
import org.tron.keystore.WalletFile;
import org.tron.protos.Contract;
import org.tron.protos.Contract.AssetIssueContract;
import org.tron.protos.Contract.IncrementalMerkleVoucherInfo;
import org.tron.protos.Contract.OutputPoint;
import org.tron.protos.Contract.OutputPointInfo;
import org.tron.protos.Protocol.Account;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.ChainParameters;
import org.tron.protos.Protocol.Exchange;
import org.tron.protos.Protocol.Proposal;
import org.tron.protos.Protocol.Transaction;
import org.tron.walletserver.WalletApi;

public class WalletApiWrapper {

  private static final Logger logger = LoggerFactory.getLogger("WalletApiWrapper");
  private WalletApi wallet;

  public String registerWallet(char[] password) throws CipherException, IOException {
    if (!WalletApi.passwordValid(password)) {
      return null;
    }

    byte[] passwd = StringUtils.char2Byte(password);

    WalletFile walletFile = WalletApi.CreateWalletFile(passwd);
    StringUtils.clear(passwd);

    String keystoreName = WalletApi.store2Keystore(walletFile);
    logout();
    return keystoreName;
  }

  public String importWallet(char[] password, byte[] priKey) throws CipherException, IOException {
    if (!WalletApi.passwordValid(password)) {
      return null;
    }
    if (!WalletApi.priKeyValid(priKey)) {
      return null;
    }

    byte[] passwd = StringUtils.char2Byte(password);

    WalletFile walletFile = WalletApi.CreateWalletFile(passwd, priKey);
    StringUtils.clear(passwd);

    String keystoreName = WalletApi.store2Keystore(walletFile);
    logout();
    return keystoreName;
  }

  public boolean changePassword(char[] oldPassword, char[] newPassword)
      throws IOException, CipherException {
    logout();
    if (!WalletApi.passwordValid(newPassword)) {
      logger.warn("Warning: ChangePassword failed, NewPassword is invalid !!");
      return false;
    }

    byte[] oldPasswd = StringUtils.char2Byte(oldPassword);
    byte[] newPasswd = StringUtils.char2Byte(newPassword);

    boolean result = WalletApi.changeKeystorePassword(oldPasswd, newPasswd);
    StringUtils.clear(oldPasswd);
    StringUtils.clear(newPasswd);

    return result;
  }

  public boolean login() throws IOException, CipherException {
    logout();
    wallet = WalletApi.loadWalletFromKeystore();

    System.out.println("Please input your password.");
    char[] password = Utils.inputPassword(false);
    byte[] passwd = StringUtils.char2Byte(password);
    StringUtils.clear(password);
    wallet.checkPassword(passwd);
    StringUtils.clear(passwd);

    if (wallet == null) {
      System.out.println("Warning: Login failed, Please registerWallet or importWallet first !!");
      return false;
    }
    wallet.setLogin();
    return true;
  }

  public void logout() {
    if (wallet != null) {
      wallet.logout();
      wallet = null;
    }
    //Neddn't logout
  }

  //password is current, will be enc by password2.
  public byte[] backupWallet() throws IOException, CipherException {
    if (wallet == null || !wallet.isLoginState()) {
      wallet = WalletApi.loadWalletFromKeystore();
      if (wallet == null) {
        System.out.println("Warning: BackupWallet failed, no wallet can be backup !!");
        return null;
      }
    }

    System.out.println("Please input your password.");
    char[] password = Utils.inputPassword(false);
    byte[] passwd = StringUtils.char2Byte(password);
    StringUtils.clear(password);
    byte[] privateKey = wallet.getPrivateBytes(passwd);
    StringUtils.clear(passwd);

    return privateKey;
  }

  public String getAddress() {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: GetAddress failed,  Please login first !!");
      return null;
    }

    return WalletApi.encode58Check(wallet.getAddress());
  }

  public Account queryAccount() {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: QueryAccount failed,  Please login first !!");
      return null;
    }

    return wallet.queryAccount();
  }

  public boolean sendCoin(String toAddress, long amount)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: SendCoin failed,  Please login first !!");
      return false;
    }
    byte[] to = WalletApi.decodeFromBase58Check(toAddress);
    if (to == null) {
      return false;
    }

    return wallet.sendCoin(to, amount);
  }

  public boolean transferAsset(String toAddress, String assertName, long amount)
      throws IOException, CipherException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: TransferAsset failed,  Please login first !!");
      return false;
    }
    byte[] to = WalletApi.decodeFromBase58Check(toAddress);
    if (to == null) {
      return false;
    }

    return wallet.transferAsset(to, assertName.getBytes(), amount);
  }

  public boolean participateAssetIssue(String toAddress, String assertName,
      long amount) throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: TransferAsset failed,  Please login first !!");
      return false;
    }
    byte[] to = WalletApi.decodeFromBase58Check(toAddress);
    if (to == null) {
      return false;
    }

    return wallet.participateAssetIssue(to, assertName.getBytes(), amount);
  }

  public boolean assetIssue(String name, String abbrName, long totalSupply, int trxNum, int icoNum,
      int precision, long startTime, long endTime, int voteScore, String description, String url,
      long freeNetLimit, long publicFreeNetLimit, HashMap<String, String> frozenSupply)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: assetIssue failed,  Please login first !!");
      return false;
    }

    Contract.AssetIssueContract.Builder builder = Contract.AssetIssueContract.newBuilder();
    builder.setOwnerAddress(ByteString.copyFrom(wallet.getAddress()));
    builder.setName(ByteString.copyFrom(name.getBytes()));
    builder.setAbbr(ByteString.copyFrom(abbrName.getBytes()));

    if (totalSupply <= 0) {
      System.out.println("totalSupply should greater than 0. but really is " + totalSupply);
      return false;
    }
    builder.setTotalSupply(totalSupply);

    if (trxNum <= 0) {
      System.out.println("trxNum should greater than 0. but really is " + trxNum);
      return false;
    }
    builder.setTrxNum(trxNum);

    if (icoNum <= 0) {
      System.out.println("num should greater than 0. but really is " + icoNum);
      return false;
    }
    builder.setNum(icoNum);

    if (precision < 0) {
      System.out.println("precision should greater or equal to 0. but really is " + precision);
      return false;
    }
    builder.setPrecision(precision);

    long now = System.currentTimeMillis();
    if (startTime <= now) {
      System.out.println("startTime should greater than now. but really is startTime("
          + startTime + ") now(" + now + ")");
      return false;
    }
    if (endTime <= startTime) {
      System.out.println("endTime should greater or equal to startTime. but really is endTime("
          + endTime + ") startTime(" + startTime + ")");
      return false;
    }

    if (freeNetLimit < 0) {
      System.out.println("freeAssetNetLimit should greater or equal to 0. but really is "
          + freeNetLimit);
      return false;
    }
    if (publicFreeNetLimit < 0) {
      System.out.println("publicFreeAssetNetLimit should greater or equal to 0. but really is "
          + publicFreeNetLimit);
      return false;
    }

    builder.setStartTime(startTime);
    builder.setEndTime(endTime);
    builder.setVoteScore(voteScore);
    builder.setDescription(ByteString.copyFrom(description.getBytes()));
    builder.setUrl(ByteString.copyFrom(url.getBytes()));
    builder.setFreeAssetNetLimit(freeNetLimit);
    builder.setPublicFreeAssetNetLimit(publicFreeNetLimit);

    for (String daysStr : frozenSupply.keySet()) {
      String amountStr = frozenSupply.get(daysStr);
      long amount = Long.parseLong(amountStr);
      long days = Long.parseLong(daysStr);
      Contract.AssetIssueContract.FrozenSupply.Builder frozenSupplyBuilder
          = Contract.AssetIssueContract.FrozenSupply.newBuilder();
      frozenSupplyBuilder.setFrozenAmount(amount);
      frozenSupplyBuilder.setFrozenDays(days);
      builder.addFrozenSupply(frozenSupplyBuilder.build());
    }

    return wallet.createAssetIssue(builder.build());
  }

  public boolean createAccount(String address)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: createAccount failed,  Please login first !!");
      return false;
    }

    byte[] addressBytes = WalletApi.decodeFromBase58Check(address);
    return wallet.createAccount(addressBytes);
  }

  public AddressPrKeyPairMessage generateAddress() {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: createAccount failed,  Please login first !!");
      return null;
    }
    return WalletApi.generateAddress();
  }


  public boolean createWitness(String url) throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: createWitness failed,  Please login first !!");
      return false;
    }

    return wallet.createWitness(url.getBytes());
  }

  public boolean updateWitness(String url) throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: updateWitness failed,  Please login first !!");
      return false;
    }

    return wallet.updateWitness(url.getBytes());
  }

  public Block getBlock(long blockNum) {
    return WalletApi.getBlock(blockNum);
  }

  public long getTransactionCountByBlockNum(long blockNum) {
    return WalletApi.getTransactionCountByBlockNum(blockNum);
  }

  public BlockExtention getBlock2(long blockNum) {
    return WalletApi.getBlock2(blockNum);
  }

  public boolean voteWitness(HashMap<String, String> witness)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: VoteWitness failed,  Please login first !!");
      return false;
    }

    return wallet.voteWitness(witness);
  }

  public Optional<WitnessList> listWitnesses() {
    try {
      return WalletApi.listWitnesses();
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<AssetIssueList> getAssetIssueList() {
    try {
      return WalletApi.getAssetIssueList();
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<AssetIssueList> getAssetIssueList(long offset, long limit) {
    try {
      return WalletApi.getAssetIssueList(offset, limit);
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public AssetIssueContract getAssetIssueByName(String assetName) {
    return WalletApi.getAssetIssueByName(assetName);
  }

  public Optional<AssetIssueList> getAssetIssueListByName(String assetName) {
    try {
      return WalletApi.getAssetIssueListByName(assetName);
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public AssetIssueContract getAssetIssueById(String assetId) {
    return WalletApi.getAssetIssueById(assetId);
  }

  public Optional<ProposalList> getProposalListPaginated(long offset, long limit) {
    try {
      return WalletApi.getProposalListPaginated(offset, limit);
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<ExchangeList> getExchangeListPaginated(long offset, long limit) {
    try {
      return WalletApi.getExchangeListPaginated(offset, limit);
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<NodeList> listNodes() {
    try {
      return WalletApi.listNodes();
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public GrpcAPI.NumberMessage getTotalTransaction() {
    return WalletApi.getTotalTransaction();
  }

  public GrpcAPI.NumberMessage getNextMaintenanceTime() {
    return WalletApi.getNextMaintenanceTime();
  }

  public boolean updateAccount(byte[] accountNameBytes)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: updateAccount failed, Please login first !!");
      return false;
    }

    return wallet.updateAccount(accountNameBytes);
  }

  public boolean setAccountId(byte[] accountIdBytes)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: setAccount failed, Please login first !!");
      return false;
    }

    return wallet.setAccountId(accountIdBytes);
  }


  public boolean updateAsset(byte[] description, byte[] url, long newLimit,
      long newPublicLimit) throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: updateAsset failed, Please login first !!");
      return false;
    }

    return wallet.updateAsset(description, url, newLimit, newPublicLimit);
  }

  public boolean freezeBalance(long frozen_balance, long frozen_duration, int resourceCode,
      String receiverAddress)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: freezeBalance failed, Please login first !!");
      return false;
    }

    return wallet.freezeBalance(frozen_balance, frozen_duration, resourceCode, receiverAddress);
  }

  public boolean buyStorage(long quantity)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: buyStorage failed, Please login first !!");
      return false;
    }

    return wallet.buyStorage(quantity);
  }

  public boolean buyStorageBytes(long bytes)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: buyStorageBytes failed, Please login first !!");
      return false;
    }

    return wallet.buyStorageBytes(bytes);
  }

  public boolean sellStorage(long storageBytes)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: sellStorage failed, Please login first !!");
      return false;
    }

    return wallet.sellStorage(storageBytes);
  }


  public boolean unfreezeBalance(int resourceCode, String receiverAddress)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: unfreezeBalance failed, Please login first !!");
      return false;
    }

    return wallet.unfreezeBalance(resourceCode, receiverAddress);
  }


  public boolean unfreezeAsset() throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: unfreezeAsset failed, Please login first !!");
      return false;
    }

    return wallet.unfreezeAsset();
  }

  public boolean withdrawBalance() throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: withdrawBalance failed, Please login first !!");
      return false;
    }

    return wallet.withdrawBalance();
  }

  public boolean createProposal(HashMap<Long, Long> parametersMap)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: createProposal failed, Please login first !!");
      return false;
    }

    return wallet.createProposal(parametersMap);
  }


  public Optional<ProposalList> getProposalsList() {
    try {
      return WalletApi.listProposals();
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<Proposal> getProposals(String id) {
    try {
      return WalletApi.getProposal(id);
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<ExchangeList> getExchangeList() {
    try {
      return WalletApi.listExchanges();
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<Exchange> getExchange(String id) {
    try {
      return WalletApi.getExchange(id);
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }

  public Optional<ChainParameters> getChainParameters() {
    try {
      return WalletApi.getChainParameters();
    } catch (Exception ex) {
      ex.printStackTrace();
      return Optional.empty();
    }
  }


  public boolean approveProposal(long id, boolean is_add_approval)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: approveProposal failed, Please login first !!");
      return false;
    }

    return wallet.approveProposal(id, is_add_approval);
  }

  public boolean deleteProposal(long id)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: deleteProposal failed, Please login first !!");
      return false;
    }

    return wallet.deleteProposal(id);
  }

  public boolean exchangeCreate(byte[] firstTokenId, long firstTokenBalance,
      byte[] secondTokenId, long secondTokenBalance)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: exchangeCreate failed, Please login first !!");
      return false;
    }

    return wallet.exchangeCreate(firstTokenId, firstTokenBalance,
        secondTokenId, secondTokenBalance);
  }

  public boolean exchangeInject(long exchangeId, byte[] tokenId, long quant)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: exchangeInject failed, Please login first !!");
      return false;
    }

    return wallet.exchangeInject(exchangeId, tokenId, quant);
  }

  public boolean exchangeWithdraw(long exchangeId, byte[] tokenId, long quant)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: exchangeWithdraw failed, Please login first !!");
      return false;
    }

    return wallet.exchangeWithdraw(exchangeId, tokenId, quant);
  }

  public boolean exchangeTransaction(long exchangeId, byte[] tokenId, long quant, long expected)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: exchangeTransaction failed, Please login first !!");
      return false;
    }

    return wallet.exchangeTransaction(exchangeId, tokenId, quant, expected);
  }

  public boolean updateSetting(byte[] contractAddress, long consumeUserResourcePercent)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: updateSetting failed,  Please login first !!");
      return false;
    }
    return wallet.updateSetting(contractAddress, consumeUserResourcePercent);

  }

  public boolean updateEnergyLimit(byte[] contractAddress, long originEnergyLimit)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: updateSetting failed,  Please login first !!");
      return false;
    }
    return wallet.updateEnergyLimit(contractAddress, originEnergyLimit);

  }

  public boolean clearContractABI(byte[] contractAddress)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: updateSetting failed,  Please login first !!");
      return false;
    }
    return wallet.clearContractABI(contractAddress);
  }

  public boolean deployContract(String name, String abiStr, String codeStr,
      long feeLimit, long value, long consumeUserResourcePercent, long originEnergyLimit,
      long tokenValue, String tokenId, String libraryAddressPair, String compilerVersion)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: createContract failed,  Please login first !!");
      return false;
    }
    return wallet
        .deployContract(name, abiStr, codeStr, feeLimit, value, consumeUserResourcePercent,
            originEnergyLimit, tokenValue, tokenId,
            libraryAddressPair, compilerVersion);
  }

  public boolean callContract(byte[] contractAddress, long callValue, byte[] data, long feeLimit,
      long tokenValue, String tokenId, boolean isConstant)
      throws CipherException, IOException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: callContract failed,  Please login first !!");
      return false;
    }

    return wallet.triggerContract(contractAddress, callValue, data, feeLimit, tokenValue, tokenId,
        isConstant);
  }

  public boolean accountPermissionUpdate(byte[] ownerAddress, String permission)
      throws IOException, CipherException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: accountPermissionUpdate failed,  Please login first !!");
      return false;
    }
    return wallet.accountPermissionUpdate(ownerAddress, permission);
  }


  public Transaction addTransactionSign(Transaction transaction)
      throws IOException, CipherException, CancelException {
    if (wallet == null || !wallet.isLoginState()) {
      logger.warn("Warning: addTransactionSign failed,  Please login first !!");
      return null;
    }
    return wallet.addTransactionSign(transaction);
  }

  public boolean sendShieldedCoin(String fromAddress, long fromAmount, List<Long> shieldedInputList,
      List<GrpcAPI.Note> shieldedOutputList, String toAddress, long toAmount)
      throws CipherException, IOException, CancelException, ZksnarkException {
    PrivateParameters.Builder builder = PrivateParameters.newBuilder();
    if (!StringUtil.isNullOrEmpty(fromAddress)) {
      byte[] from = WalletApi.decodeFromBase58Check(fromAddress);
      if (from == null) {
        return false;
      }
      builder.setTransparentFromAddress(ByteString.copyFrom(from));
      builder.setFromAmount(fromAmount);
    }

    if (!StringUtil.isNullOrEmpty(toAddress)) {
      byte[] to = WalletApi.decodeFromBase58Check(toAddress);
      if (to == null) {
        return false;
      }
      builder.setTransparentToAddress(ByteString.copyFrom(to));
      builder.setToAmount(toAmount);
    }

    if (shieldedInputList.size() > 0) {
      OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
      for (int i = 0; i < shieldedInputList.size(); ++i) {
        ShieldedNoteInfo noteInfo = ShieldedWrapper.getInstance().getUtxoMapNote().get(shieldedInputList.get(i));
        OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
        outPointBuild.setHash(ByteString.copyFrom(ByteArray.fromHexString(noteInfo.getTrxId())));
        outPointBuild.setIndex(noteInfo.getIndex());
        request.addOutPoints(outPointBuild.build());
      }
      Optional<IncrementalMerkleVoucherInfo> merkleVoucherInfo =
              WalletApi.GetMerkleTreeVoucherInfo(request.build(), true);
      if (!merkleVoucherInfo.isPresent()
          || merkleVoucherInfo.get().getVouchersCount() != shieldedInputList.size()) {
        System.out.println("Can't get all merkel tree, please check the notes.");
        return false;
      }

      for (int i = 0; i < shieldedInputList.size(); ++i) {
        ShieldedNoteInfo noteInfo = ShieldedWrapper.getInstance().getUtxoMapNote().get(shieldedInputList.get(i));
        if (i == 0) {
          String shieldedAddress = noteInfo.getPaymentAddress();
          ShieldedAddressInfo addressInfo =
                  ShieldedWrapper.getInstance().getShieldedAddressInfoMap().get(shieldedAddress);
          SpendingKey spendingKey = new SpendingKey(addressInfo.getSk());
          ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();

          builder.setAsk(ByteString.copyFrom(expandedSpendingKey.getAsk()));
          builder.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
          builder.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));
        }

        Note.Builder noteBuild = Note.newBuilder();
        noteBuild.setPaymentAddress(noteInfo.getPaymentAddress());
        noteBuild.setValue(noteInfo.getValue());
        noteBuild.setRcm(ByteString.copyFrom(noteInfo.getR()));
        noteBuild.setMemo(ByteString.copyFrom(noteInfo.getMemo()));

        System.out.println("address " + noteInfo.getPaymentAddress());
        System.out.println("value " + noteInfo.getValue());
        System.out.println("rcm " + ByteArray.toHexString(noteInfo.getR()));
        System.out.println("trxId " + noteInfo.getTrxId());
        System.out.println("index " + noteInfo.getIndex());
        System.out.println("memo " + ZenUtils.getMemo(noteInfo.getMemo()));

        SpendNote.Builder spendNoteBuilder = SpendNote.newBuilder();
        spendNoteBuilder.setNote(noteBuild.build());
        spendNoteBuilder.setAlpha(ByteString.copyFrom(getRcm()));
        spendNoteBuilder.setVoucher(merkleVoucherInfo.get().getVouchers(i));
        spendNoteBuilder.setPath(merkleVoucherInfo.get().getPaths(i));

        builder.addShieldedSpends(spendNoteBuilder.build());
      }
    } else {
      byte[] ovk = ByteArray
          .fromHexString("030c8c2bc59fb3eb8afb047a8ea4b028743d23e7d38c6fa30908358431e2314d");
      builder.setOvk(ByteString.copyFrom(ovk));
    }

    if (shieldedOutputList.size() > 0) {
      for (int i = 0; i < shieldedOutputList.size(); ++i) {
        builder.addShieldedReceives(
            ReceiveNote.newBuilder().setNote(shieldedOutputList.get(i)).build());
      }
    }

    return WalletApi.sendShieldedCoin(builder.build(), wallet);
  }

  public boolean sendShieldedCoinWithoutAsk(String fromAddress, long fromAmount,
      List<Long> shieldedInputList,
      List<GrpcAPI.Note> shieldedOutputList, String toAddress, long toAmount)
      throws CipherException, IOException, CancelException, ZksnarkException {
    PrivateParametersWithoutAsk.Builder builder = PrivateParametersWithoutAsk.newBuilder();
    if (!StringUtil.isNullOrEmpty(fromAddress)) {
      byte[] from = WalletApi.decodeFromBase58Check(fromAddress);
      if (from == null) {
        return false;
      }
      builder.setTransparentFromAddress(ByteString.copyFrom(from));
      builder.setFromAmount(fromAmount);
    }

    if (!StringUtil.isNullOrEmpty(toAddress)) {
      byte[] to = WalletApi.decodeFromBase58Check(toAddress);
      if (to == null) {
        return false;
      }
      builder.setTransparentToAddress(ByteString.copyFrom(to));
      builder.setToAmount(toAmount);
    }

    byte[] ask = new byte[32];
    if (shieldedInputList.size() > 0) {
      OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
      for (int i = 0; i < shieldedInputList.size(); ++i) {
        ShieldedNoteInfo noteInfo = ShieldedWrapper.getInstance().getUtxoMapNote().get(shieldedInputList.get(i));
        OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
        outPointBuild.setHash(ByteString.copyFrom(ByteArray.fromHexString(noteInfo.getTrxId())));
        outPointBuild.setIndex(noteInfo.getIndex());
        request.addOutPoints(outPointBuild.build());
      }
      Optional<IncrementalMerkleVoucherInfo> merkleVoucherInfo =
          WalletApi.GetMerkleTreeVoucherInfo(request.build(), true);
      if (!merkleVoucherInfo.isPresent()
          || merkleVoucherInfo.get().getVouchersCount() != shieldedInputList.size()) {
        System.out.println("Can't get all merkel tree, please check the notes.");
        return false;
      }

      for (int i = 0; i < shieldedInputList.size(); ++i) {
        ShieldedNoteInfo noteInfo = ShieldedWrapper.getInstance().getUtxoMapNote().get(shieldedInputList.get(i));
        if (i == 0) {
          String shieldAddress = noteInfo.getPaymentAddress();
          ShieldedAddressInfo addressInfo =
                  ShieldedWrapper.getInstance().getShieldedAddressInfoMap().get(shieldAddress);
          SpendingKey spendingKey = new SpendingKey(addressInfo.getSk());
          ExpandedSpendingKey expandedSpendingKey = spendingKey.expandedSpendingKey();

          System.arraycopy(expandedSpendingKey.getAsk(), 0, ask, 0, 32);
          builder.setAk(ByteString.copyFrom(
              ExpandedSpendingKey.getAkFromAsk(expandedSpendingKey.getAsk())));
          builder.setNsk(ByteString.copyFrom(expandedSpendingKey.getNsk()));
          builder.setOvk(ByteString.copyFrom(expandedSpendingKey.getOvk()));
        }

        Note.Builder noteBuild = Note.newBuilder();
        noteBuild.setPaymentAddress(noteInfo.getPaymentAddress());
        noteBuild.setValue(noteInfo.getValue());
        noteBuild.setRcm(ByteString.copyFrom(noteInfo.getR()));
        noteBuild.setMemo(ByteString.copyFrom(noteInfo.getMemo()));

        System.out.println("address " + noteInfo.getPaymentAddress());
        System.out.println("value " + noteInfo.getValue());
        System.out.println("rcm " + ByteArray.toHexString(noteInfo.getR()));
        System.out.println("trxId " + noteInfo.getTrxId());
        System.out.println("index " + noteInfo.getIndex());
        System.out.println("memo " + ZenUtils.getMemo(noteInfo.getMemo()));

        SpendNote.Builder spendNoteBuilder = SpendNote.newBuilder();
        spendNoteBuilder.setNote(noteBuild.build());
        spendNoteBuilder.setAlpha(ByteString.copyFrom(getRcm()));
        spendNoteBuilder.setVoucher(merkleVoucherInfo.get().getVouchers(i));
        spendNoteBuilder.setPath(merkleVoucherInfo.get().getPaths(i));

        builder.addShieldedSpends(spendNoteBuilder.build());
      }
    } else {
      byte[] ovk = ByteArray
          .fromHexString("030c8c2bc59fb3eb8afb047a8ea4b028743d23e7d38c6fa30908358431e2314d");
      builder.setOvk(ByteString.copyFrom(ovk));
    }

    if (shieldedOutputList.size() > 0) {
      for (int i = 0; i < shieldedOutputList.size(); ++i) {
        builder.addShieldedReceives(
            ReceiveNote.newBuilder().setNote(shieldedOutputList.get(i)).build());
      }
    }

    return WalletApi.sendShieldedCoinWithoutAsk(builder.build(), ask, wallet);
  }

  public boolean resetShieldedNote() {
    System.out.println("Start to reset reset shielded notes, please wait ...");
    ShieldedWrapper.getInstance().setResetNote(true);
    return true;
  }

  public boolean scanNoteByIvk(final String ivk, long start, long end ) {
    GrpcAPI.IvkDecryptParameters ivkDecryptParameters = IvkDecryptParameters.newBuilder()
        .setStartBlockIndex(start)
        .setEndBlockIndex(end)
        .setIvk(ByteString.copyFrom(ByteArray.fromHexString(ivk)))
        .build();

    Optional<DecryptNotes> decryptNotes = WalletApi.scanNoteByIvk(ivkDecryptParameters, true);
    if(!decryptNotes.isPresent()){
      logger.info("scanNoteByIvk failed !!!");
    } else {
      for(int i=0; i<decryptNotes.get().getNoteTxsList().size();i++) {
        NoteTx noteTx = decryptNotes.get().getNoteTxs(i);
        Note note = noteTx.getNote();
        logger.info("\ntxid:{}\nindex:{}\naddress:{}\nrcm:{}\nvalue:{}\nmemo:{}",
            ByteArray.toHexString(noteTx.getTxid().toByteArray()),
            noteTx.getIndex(),
            note.getPaymentAddress(),
            ByteArray.toHexString(note.getRcm().toByteArray()),
            note.getValue(),
            ZenUtils.getMemo(note.getMemo().toByteArray()));
      }
      logger.info("complete.");
    }
    return true;
  }

  public boolean scanAndMarkNoteByAddress(final String shieldedAddress, long start, long end ) {
    ShieldedAddressInfo addressInfo = ShieldedWrapper.getInstance().getShieldedAddressInfoMap().get(shieldedAddress);
    if (addressInfo == null ) {
      System.out.println("Can't find shieldedAddress in local, please check shieldedAddress.");
      return false;
    }

    try {
      IvkDecryptAndMarkParameters.Builder builder = IvkDecryptAndMarkParameters.newBuilder();
      builder.setStartBlockIndex(start);
      builder.setEndBlockIndex(end);
      builder.setIvk(ByteString.copyFrom(addressInfo.getIvk()));
      builder.setAk(ByteString.copyFrom(addressInfo.getFullViewingKey().getAk()));
      builder.setNk(ByteString.copyFrom(addressInfo.getFullViewingKey().getNk()));

      Optional<DecryptNotesMarked> decryptNotes = WalletApi.scanAndMarkNoteByIvk(builder.build());
      if(decryptNotes.isPresent()){
        for(int i=0; i<decryptNotes.get().getNoteTxsList().size();i++) {
          DecryptNotesMarked.NoteTx noteTx = decryptNotes.get().getNoteTxs(i);
          Note note = noteTx.getNote();
          logger.info("\ntxid:{}\nindex:{}\nisSpend:{}\naddress:{}\nrcm:{}\nvalue:{}\nmemo:{}",
              ByteArray.toHexString(noteTx.getTxid().toByteArray()),
              noteTx.getIndex(),
              noteTx.getIsSpend(),
              note.getPaymentAddress(),
              ByteArray.toHexString(note.getRcm().toByteArray()),
              note.getValue(),
              ZenUtils.getMemo(note.getMemo().toByteArray()));
        }
      } else {
        logger.info("scanAndMarkNoteByIvk failed !!!");
      }
    } catch ( Exception e) {

    }
    logger.info("complete.");
    return true;
  }

  public boolean scanShieldedNoteByovk(final String shieldedAddress, long start, long end ) {
    GrpcAPI.OvkDecryptParameters ovkDecryptParameters = OvkDecryptParameters.newBuilder()
        .setStartBlockIndex(start)
        .setEndBlockIndex(end)
        .setOvk(ByteString.copyFrom(ByteArray.fromHexString(shieldedAddress)))
        .build();

    Optional<DecryptNotes> decryptNotes = WalletApi.scanNoteByOvk(ovkDecryptParameters, true);
    if( !decryptNotes.isPresent() ){
      logger.info("ScanNoteByOvk failed !!!");
    }else{
      for(int i=0; i<decryptNotes.get().getNoteTxsList().size();i++) {
        NoteTx noteTx = decryptNotes.get().getNoteTxs(i);
        Note note = noteTx.getNote();
        logger.info("\ntxid:{}\nindex:{}\npaymentAddress:{}\nrcm:{}\nmemo:{}\nvalue:{}",
            ByteArray.toHexString(noteTx.getTxid().toByteArray()),
            noteTx.getIndex(),
            note.getPaymentAddress(),
            ByteArray.toHexString(note.getRcm().toByteArray()),
            ZenUtils.getMemo(note.getMemo().toByteArray()),
            note.getValue());
      }
      logger.info("complete.");
    }
    return true;
  }

  public Optional<ShieldedAddressInfo> getNewShieldedAddress() {
    ShieldedAddressInfo addressInfo = new ShieldedAddressInfo();
    try {
        Optional<BytesMessage> sk = WalletApi.getSpendingKey();
        Optional<DiversifierMessage> d = WalletApi.getDiversifier();

        Optional<ExpandedSpendingKeyMessage> expandedSpendingKeyMessage = WalletApi.getExpandedSpendingKey(sk.get());

        BytesMessage.Builder askBuilder = BytesMessage.newBuilder();
        askBuilder.setValue(expandedSpendingKeyMessage.get().getAsk());
        Optional<BytesMessage> ak = WalletApi.getAkFromAsk(askBuilder.build());

        BytesMessage.Builder nskBuilder = BytesMessage.newBuilder();
        nskBuilder.setValue(expandedSpendingKeyMessage.get().getNsk());
        Optional<BytesMessage> nk = WalletApi.getNkFromNsk(nskBuilder.build());

        ViewingKeyMessage.Builder viewBuilder = ViewingKeyMessage.newBuilder();
        viewBuilder.setAk(ak.get().getValue());
        viewBuilder.setNk(nk.get().getValue());
        Optional<IncomingViewingKeyMessage> ivk = WalletApi.getIncomingViewingKey(viewBuilder.build());

        IncomingViewingKeyDiversifierMessage.Builder builder = IncomingViewingKeyDiversifierMessage.newBuilder();
        builder.setD(d.get());
        builder.setIvk(ivk.get());
        Optional<PaymentAddressMessage> addressMessage = WalletApi.getZenPaymentAddress(builder.build());
        addressInfo.setSk(sk.get().getValue().toByteArray());
        addressInfo.setD(new DiversifierT(d.get().getD().toByteArray()));
        addressInfo.setIvk(ivk.get().getIvk().toByteArray());
        addressInfo.setOvk(expandedSpendingKeyMessage.get().getOvk().toByteArray());
        addressInfo.setPkD(addressMessage.get().getPkD().toByteArray());

      if (addressInfo.validateCheck()) {
        return Optional.of(addressInfo);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return Optional.empty();
  }

  public Optional<ShieldedAddressInfo> getNewShieldedAddressBySkAndD(byte[] sk, byte[] d) {
    ShieldedAddressInfo addressInfo = new ShieldedAddressInfo();
    try {
      BytesMessage.Builder skBuilder = BytesMessage.newBuilder();
      skBuilder.setValue(ByteString.copyFrom(sk));

      DiversifierMessage.Builder dBuilder = DiversifierMessage.newBuilder();
      dBuilder.setD(ByteString.copyFrom(d));

      Optional<ExpandedSpendingKeyMessage> expandedSpendingKeyMessage = WalletApi
          .getExpandedSpendingKey(skBuilder.build());

      BytesMessage.Builder askBuilder = BytesMessage.newBuilder();
      askBuilder.setValue(expandedSpendingKeyMessage.get().getAsk());
      Optional<BytesMessage> ak = WalletApi.getAkFromAsk(askBuilder.build());

      BytesMessage.Builder nskBuilder = BytesMessage.newBuilder();
      nskBuilder.setValue(expandedSpendingKeyMessage.get().getNsk());
      Optional<BytesMessage> nk = WalletApi.getNkFromNsk(nskBuilder.build());

      ViewingKeyMessage.Builder viewBuilder = ViewingKeyMessage.newBuilder();
      viewBuilder.setAk(ak.get().getValue());
      viewBuilder.setNk(nk.get().getValue());
      Optional<IncomingViewingKeyMessage> ivk = WalletApi
          .getIncomingViewingKey(viewBuilder.build());

      IncomingViewingKeyDiversifierMessage.Builder builder = IncomingViewingKeyDiversifierMessage
          .newBuilder();
      builder.setD(dBuilder.build());
      builder.setIvk(ivk.get());
      Optional<PaymentAddressMessage> addressMessage = WalletApi
          .getZenPaymentAddress(builder.build());
      addressInfo.setSk(sk);
      addressInfo.setD(new DiversifierT(d));
      addressInfo.setIvk(ivk.get().getIvk().toByteArray());
      addressInfo.setOvk(expandedSpendingKeyMessage.get().getOvk().toByteArray());
      addressInfo.setPkD(addressMessage.get().getPkD().toByteArray());

      if (addressInfo.validateCheck()) {
        return Optional.of(addressInfo);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return Optional.empty();
  }

  public String getShieldedNulltifier(long index) {
    ShieldedNoteInfo noteInfo = ShieldedWrapper.getInstance().getUtxoMapNote().get(index);
    if (noteInfo == null) {
      return null;
    }

    OutputPointInfo.Builder request = OutputPointInfo.newBuilder();
    OutputPoint.Builder outPointBuild = OutputPoint.newBuilder();
    outPointBuild.setHash(ByteString.copyFrom(ByteArray.fromHexString(noteInfo.getTrxId())));
    outPointBuild.setIndex(noteInfo.getIndex());
    request.addOutPoints(outPointBuild.build());
    Optional<IncrementalMerkleVoucherInfo> merkleVoucherInfo =
        WalletApi.GetMerkleTreeVoucherInfo(request.build(), true);
    if (!merkleVoucherInfo.isPresent() || merkleVoucherInfo.get().getVouchersCount() < 1) {
      System.out.println("get merkleVoucherInfo failure.");
      return null;
    }

    Note.Builder noteBuild = Note.newBuilder();
    noteBuild.setPaymentAddress(noteInfo.getPaymentAddress());
    noteBuild.setValue(noteInfo.getValue());
    noteBuild.setRcm(ByteString.copyFrom(noteInfo.getR()));
    noteBuild.setMemo(ByteString.copyFrom(noteInfo.getMemo()));

    System.out.println("address " + noteInfo.getPaymentAddress());
    System.out.println("value " + noteInfo.getValue());
    System.out.println("rcm " + ByteArray.toHexString(noteInfo.getR()));
    System.out.println("trxId " + noteInfo.getTrxId());
    System.out.println("index " + noteInfo.getIndex());
    System.out.println("memo " + ZenUtils.getMemo(noteInfo.getMemo()));

    String shieldedAddress = noteInfo.getPaymentAddress();
    ShieldedAddressInfo addressInfo = ShieldedWrapper.getInstance().getShieldedAddressInfoMap()
        .get(shieldedAddress);

    SpendingKey spendingKey = new SpendingKey(addressInfo.getSk());

    try {
      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      NfParameters.Builder builder = NfParameters.newBuilder();
      builder.setNote(noteBuild.build());
      builder.setVoucher(merkleVoucherInfo.get().getVouchers(0));
      builder.setAk(ByteString.copyFrom(fullViewingKey.getAk()));
      builder.setNk(ByteString.copyFrom(fullViewingKey.getNk()));

      Optional<BytesMessage> nullifier = WalletApi.createShieldedNullifier(builder.build());
      return ByteArray.toHexString(nullifier.get().getValue().toByteArray());

    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  public byte[] getRcm() {
    return WalletApi.getRcm().get().getValue().toByteArray();
  }

}