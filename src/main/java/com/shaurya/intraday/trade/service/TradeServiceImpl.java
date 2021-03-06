/**
 * 
 */
package com.shaurya.intraday.trade.service;

import static com.shaurya.intraday.util.HelperUtil.getDayEndTime;
import static com.shaurya.intraday.util.HelperUtil.getDayStartTime;
import static com.shaurya.intraday.util.HelperUtil.getNthLastKeyEntry;
import static com.shaurya.intraday.util.HelperUtil.getPrevTradingDate;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.shaurya.intraday.builder.TradeBuilder;
import com.shaurya.intraday.constant.Constants;
import com.shaurya.intraday.entity.HistoricalCandle;
import com.shaurya.intraday.entity.Trade;
import com.shaurya.intraday.entity.TradeStrategy;
import com.shaurya.intraday.enums.IntervalType;
import com.shaurya.intraday.enums.PositionType;
import com.shaurya.intraday.enums.StrategyType;
import com.shaurya.intraday.enums.TradeExitReason;
import com.shaurya.intraday.indicator.ADX;
import com.shaurya.intraday.indicator.ATR;
import com.shaurya.intraday.indicator.EMA;
import com.shaurya.intraday.indicator.MACD;
import com.shaurya.intraday.indicator.RSI;
import com.shaurya.intraday.model.ADXModel;
import com.shaurya.intraday.model.ATRModel;
import com.shaurya.intraday.model.Candle;
import com.shaurya.intraday.model.IndicatorValue;
import com.shaurya.intraday.model.MACDModel;
import com.shaurya.intraday.model.MailAccount;
import com.shaurya.intraday.model.RSIModel;
import com.shaurya.intraday.model.StrategyModel;
import com.shaurya.intraday.query.builder.TradeQueryBuilder;
import com.shaurya.intraday.repo.JpaRepo;
import com.shaurya.intraday.util.MailSender;
import com.shaurya.intraday.util.StringUtil;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;

/**
 * @author Shaurya
 *
 */
@Service
public class TradeServiceImpl implements TradeService {
	@Autowired
	private JpaRepo<Trade> tradeRepo;
	@Autowired
	private JpaRepo<TradeStrategy> strategyRepo;
	@Autowired
	private JpaRepo<HistoricalCandle> histCandleRepo;
	@Autowired
	private LoginService loginService;
	@Autowired
	private TradeProcessor processor;
	@Autowired
	private MailAccount mailAccount;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.shaurya.intraday.trade.service.TradeService#openTrade(com.shaurya.
	 * intraday.model.StrategyModel)
	 */
	@Override
	public StrategyModel openTrade(StrategyModel model) {
		Trade openTrade = TradeBuilder.convert(model);
		openTrade = tradeRepo.update(openTrade);
		return TradeBuilder.reverseConvert(openTrade, true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.shaurya.intraday.trade.service.TradeService#closeTrade(com.shaurya.
	 * intraday.model.StrategyModel)
	 */
	@Override
	public StrategyModel closeTrade(StrategyModel model, TradeExitReason reason) {
		Trade openTrade = fetchOpenTradeEntityBySecurity(model.getSecurity());
		if (openTrade == null) {
			throw new RuntimeException("no open trade found for " + model.toString());
		}
		openTrade.setTradeExitPrice(model.getTradePrice());
		openTrade.setStatus((byte) 0);
		openTrade.setTradeExitReason(reason.getId());
		openTrade = tradeRepo.update(openTrade);

		return TradeBuilder.reverseConvert(openTrade, false);
	}

	private Trade fetchOpenTradeEntityBySecurity(String securityName) {
		List<Trade> tradeList = tradeRepo
				.fetchByQuery(TradeQueryBuilder.queryToFetchOpenTradeBySecurityName(securityName));
		if (tradeList != null && !tradeList.isEmpty()) {
			return tradeList.get(0);
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.shaurya.intraday.trade.service.TradeService#fetchOpenTradeBySecurity(
	 * java.lang.String)
	 */
	@Override
	public StrategyModel fetchOpenTradeBySecurity(String security) {
		Trade openTrade = fetchOpenTradeEntityBySecurity(security);
		if (openTrade == null) {
			System.out.println("no open trade found for name ::" + security);
			return null;
		}
		return TradeBuilder.reverseConvert(openTrade, true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.shaurya.intraday.trade.service.TradeService#getTradeStrategy()
	 */
	@Override
	public Map<StrategyModel, StrategyType> getTradeStrategy() {
		Map<StrategyModel, StrategyType> sMap = new HashMap<>();
		List<TradeStrategy> strategyList = tradeRepo
				.fetchByQuery(TradeQueryBuilder.queryToFetchSecurityTradeStrategy());
		if (strategyList != null && !strategyList.isEmpty()) {
			for (TradeStrategy sl : strategyList) {
				StrategyModel model = new StrategyModel();
				model.setSecurity(sl.getSecurityName());
				model.setSecurityToken(sl.getSecurityToken());
				model.setPreferedPosition(PositionType.getEnumById(sl.getPreferedPosition()));
				model.setMarginMultiplier(sl.getMarginMultiplier());
				model.setMarginPortion(sl.getMarginPortion());
				sMap.put(model, StrategyType.getEnumById(sl.getStrategyType()));
			}
		}
		return sMap;
	}

	@Override
	public Map<StrategyModel, StrategyType> getMonitorStrategy() {
		Map<StrategyModel, StrategyType> sMap = new HashMap<>();
		List<TradeStrategy> strategyList = tradeRepo
				.fetchByQuery(TradeQueryBuilder.queryToFetchSecurityMonitorStrategy());
		if (strategyList != null && !strategyList.isEmpty()) {
			for (TradeStrategy sl : strategyList) {
				StrategyModel model = new StrategyModel();
				model.setSecurity(sl.getSecurityName());
				model.setSecurityToken(sl.getSecurityToken());
				model.setPreferedPosition(PositionType.getEnumById(sl.getPreferedPosition()));
				model.setMarginMultiplier(sl.getMarginMultiplier());
				sMap.put(model, StrategyType.getEnumById(sl.getStrategyType()));
			}
		}
		return sMap;
	}

	@Override
	public Map<StrategyModel, StrategyType> getAllTradeStrategy() {
		Map<StrategyModel, StrategyType> sMap = new HashMap<>();
		List<TradeStrategy> strategyList = tradeRepo
				.fetchByQuery(TradeQueryBuilder.queryToFetchSecurityAllTradeStrategy());
		if (strategyList != null && !strategyList.isEmpty()) {
			for (TradeStrategy sl : strategyList) {
				StrategyModel model = new StrategyModel();
				model.setSecurity(sl.getSecurityName());
				model.setSecurityToken(sl.getSecurityToken());
				model.setPreferedPosition(PositionType.getEnumById(sl.getPreferedPosition()));
				model.setMarginMultiplier(sl.getMarginMultiplier());
				sMap.put(model, StrategyType.getEnumById(sl.getStrategyType()));
			}
		}
		return sMap;
	}

	@Override
	public void updateStrategyStocks(List<StrategyModel> smList) {
		for (StrategyModel sm : smList) {
			TradeStrategy ts = TradeBuilder.convertStrategyModelToEntity(sm);
			strategyRepo.update(ts);
		}
	}

	@Override
	public List<Candle> getPrevDayCandles(String securityName) {
		List<Candle> cList = new ArrayList<>();
		List<HistoricalCandle> prevCandles = tradeRepo
				.fetchByQuery(TradeQueryBuilder.nativeQueryToFetchPrevDayCandles(securityName));
		if (prevCandles != null && !prevCandles.isEmpty()) {
			for (HistoricalCandle pc : prevCandles) {
				cList.add(TradeBuilder.reverseConvert(pc, 0));
			}
		}
		Collections.sort(cList);
		return cList;
	}

	@Override
	public List<Candle> getPrevDayCandles(Long instrumentToken, Date currentDate) throws IOException, KiteException {
		Map<Long, String> nameTokenMap = getNameTokenMap();
		List<Candle> cList = new ArrayList<>();
		Date from = getPrevTradingDate(currentDate);
		List<HistoricalData> hd;
		do {
			hd = loginService.getSdkClient().getHistoricalData(from, currentDate, instrumentToken.toString(),
					IntervalType.MINUTE_1.getDesc(), false, false).dataArrayList;
			from = getPrevTradingDate(from);
		} while (hd == null || hd.size() <= 200);

		for (HistoricalData d : hd) {
			cList.add(
					TradeBuilder.convertHistoricalDataToCandle(d, nameTokenMap.get(instrumentToken), instrumentToken));
		}
		return cList;
	}

	@Override
	public List<Candle> getPrevDayCandles(Long instrumentToken, IntervalType interval, Date from, Date to,
			int candleCount) {
		Map<Long, String> nameTokenMap = getNameTokenMap();
		List<Candle> cList = new ArrayList<>();
		try {
			List<HistoricalData> hd;
			do {
				Thread.sleep(500);
				hd = loginService.getSdkClient().getHistoricalData(from, to, instrumentToken.toString(),
						interval.getDesc(), false, false).dataArrayList;
				from = getPrevTradingDate(from);
			} while (hd == null || hd.size() <= candleCount);
			for (HistoricalData d : hd) {
				cList.add(TradeBuilder.convertHistoricalDataToCandle(d, nameTokenMap.get(instrumentToken),
						instrumentToken));
			}
		} catch (KiteException | Exception e) {
			System.out.println("Error fetching historical data :: " + StringUtil.getStackTraceInStringFmt(e));
			MailSender
					.sendMail(Constants.TO_MAIL, Constants.TO_NAME,
							Constants.PREV_DAY_CANDLE, "Error fetching historical data :: " + e.getMessage()
									+ "\n for : " + instrumentToken + "\n from : " + from + "\n to : " + to,
							mailAccount);
		}
		return cList;
	}

	@Override
	public void deletePrevDayCandlesAndStrategy() {
		tradeRepo.runNativeQueryForUpdate(TradeQueryBuilder.nativeQueryToDeletePrevDayCandles());
		tradeRepo.runNativeQueryForUpdate(TradeQueryBuilder.nativeQueryToDeletePrevDayStrategy());
	}

	@Override
	public void incrementDayForMonitorStocks() {
		tradeRepo.runNativeQueryForUpdate(TradeQueryBuilder.nativeQueryToUpdatePrevDayCandles());
		tradeRepo.runNativeQueryForUpdate(TradeQueryBuilder.nativeQueryToUpdatePrevDayStrategy());
	}

	@Override
	public Map<Long, String> getNameTokenMap() {
		Map<Long, String> nameTokenMap = new HashMap<>();
		List<Object[]> nameTokenList = tradeRepo.runNativeQuery(TradeQueryBuilder.nativeQueryToFetchNameTokenMap());
		if (nameTokenList != null && !nameTokenList.isEmpty()) {
			for (Object[] nt : nameTokenList) {
				nameTokenMap.put(Long.parseLong(nt[1].toString()), nt[0].toString());
			}
		}
		return nameTokenMap;
	}

	@Override
	public void createHistoricalCandle(Candle candle) {
		HistoricalCandle hc = TradeBuilder.convertToHistoricalcandle(candle);
		histCandleRepo.update(hc);
	}

	@Override
	public Integer fetchNumberOfTradesForTheDay() {
		List<Trade> tradeList = tradeRepo.fetchByQuery(TradeQueryBuilder.queryToFetchDayTrades(
				getDateStringFormat(getDayStartTime().getTime()), getDateStringFormat(getDayEndTime().getTime())));
		if (tradeList != null && !tradeList.isEmpty()) {
			return tradeList.size();
		}
		return 0;
	}

	@Override
	public void sendPNLStatement() {
		Map<String, List<Trade>> securityTradeMap = new HashMap<>();
		List<Trade> tradeList = tradeRepo.fetchByQuery(TradeQueryBuilder.queryToFetchDayTrades(
				getDateStringFormat(getDayStartTime().getTime()), getDateStringFormat(getDayEndTime().getTime())));
		for (Trade t : tradeList) {
			if (securityTradeMap.get(t.getSecurityName()) == null) {
				securityTradeMap.put(t.getSecurityName(), new ArrayList<>());
			}
			securityTradeMap.get(t.getSecurityName()).add(t);
		}
		int successfullTrade = 0;
		int unsuccessfullTrade = 0;
		double pnl = 0;
		for (Entry<String, List<Trade>> te : securityTradeMap.entrySet()) {
			double brokerage = 0;
			double scripPnl = 0;
			double buyTradeAmount = 0;
			double sellTradeAmount = 0;
			for (Trade t : te.getValue()) {
				double tradePnL = 0;
				switch (PositionType.getEnumById(t.getPositionType().intValue())) {
				case LONG:
					buyTradeAmount += t.getTradeEntryPrice() * t.getQuantity();
					sellTradeAmount += t.getTradeExitPrice() * t.getQuantity();
					tradePnL = ((t.getTradeExitPrice() - t.getTradeEntryPrice()) * t.getQuantity());
					scripPnl += tradePnL;
					if (tradePnL > 0) {
						successfullTrade++;
					} else {
						unsuccessfullTrade++;
					}
					break;
				case SHORT:
					sellTradeAmount += t.getTradeEntryPrice() * t.getQuantity();
					buyTradeAmount += t.getTradeExitPrice() * t.getQuantity();
					tradePnL = ((t.getTradeEntryPrice() - t.getTradeExitPrice()) * t.getQuantity());
					scripPnl += tradePnL;
					if (tradePnL > 0) {
						successfullTrade++;
					} else {
						unsuccessfullTrade++;
					}
					break;
				default:
					break;
				}
			}
			brokerage = brokerageCharge(buyTradeAmount, sellTradeAmount);
			scripPnl = scripPnl - brokerage;
			pnl += scripPnl;
		}
		MailSender.sendMail(Constants.TO_MAIL, Constants.TO_NAME, Constants.DAY_TRADE_SUMMARY,
				"Date : " + new Date() + "\n" + "Total Profit/Loss : " + pnl + "\n" + "Total trades : "
						+ (successfullTrade + unsuccessfullTrade) + "\n" + "Succefull trades : " + successfullTrade
						+ "\n" + "unsuccessfull trades : " + unsuccessfullTrade,
				mailAccount);
	}

	private double brokerageCharge(double buyTradePrice, double sellTradePrice) {
		double turnover = (buyTradePrice + sellTradePrice);
		double brokerage = Math.min((buyTradePrice * 0.0001), 20) + Math.min((sellTradePrice * 0.0001), 20);
		double stt = 0.00025 * (sellTradePrice);
		double transactionCharge = (0.0000325 * buyTradePrice) + (0.0000325 * sellTradePrice);
		double gst = 0.18 * (transactionCharge + brokerage);
		double sebiCharge = (0.0000015 * buyTradePrice) + (0.0000015 * sellTradePrice);
		double stampCharge = (0.00003 * buyTradePrice) + (0.00003 * sellTradePrice);

		return brokerage + stt + transactionCharge + gst + sebiCharge + stampCharge;
	}

	private String getDateStringFormat(Date refDate) {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String frmDate = format.format(refDate);
		return frmDate;
	}

	@Override
	public void testIndicator() throws IOException, KiteException {
		ATRModel atr;
		RSIModel rsi;
		MACDModel macd;
		ADXModel adx;
		TreeMap<Date, IndicatorValue> fastEmaMap;
		TreeMap<Date, IndicatorValue> slowEmaMap;
		TreeMap<Date, IndicatorValue> ema200Map;

		List<Candle> cList = getPrevDayCandles(5097729l, new Date());

		atr = ATR.calculateATR(cList, 14);
		rsi = RSI.calculateRSI(cList);
		adx = ADX.calculateADX(cList);
		fastEmaMap = EMA.calculateEMA(20, cList);
		slowEmaMap = EMA.calculateEMA(50, cList);
		macd = MACD.calculateMACD(fastEmaMap, slowEmaMap, 20);
		ema200Map = EMA.calculateEMA(200, cList);

		Date lastDataDate = getNthLastKeyEntry(macd.getMacdMap(), 1);
		System.out.println("sendInitSetupDataMail last date :: " + lastDataDate);
		IndicatorValue atrIv = atr.getAtrMap().lastEntry().getValue();
		IndicatorValue adxIv = adx.getAdx();
		IndicatorValue rsiIv = rsi.getRsiMap().lastEntry().getValue();
		IndicatorValue fastEma = fastEmaMap.lastEntry().getValue();
		IndicatorValue slowEma = slowEmaMap.lastEntry().getValue();
		IndicatorValue macdIv = macd.getMacdMap().lastEntry().getValue();
		IndicatorValue macdSignalIv = macd.getSignalMap().lastEntry().getValue();
		IndicatorValue ema200 = ema200Map.get(lastDataDate);
		System.out.println("sendInitSetupDataMail atr :: " + atrIv.toString());
		System.out.println("sendInitSetupDataMail adx :: " + adxIv.toString());
		System.out.println("sendInitSetupDataMail rsi :: " + rsiIv.toString());
		System.out.println("sendInitSetupDataMail fast ema :: " + fastEma.toString());
		System.out.println("sendInitSetupDataMail slow ema :: " + slowEma.toString());
		System.out.println("sendInitSetupDataMail macd :: " + macdIv.toString());
		System.out.println("sendInitSetupDataMail macd signal :: " + macdSignalIv.toString());
		System.out.println("sendInitSetupDataMail 200 ema :: " + ema200.toString());
		String mailbody = "ATR : " + atrIv.toString() + "\n" + "ADX : " + adxIv.toString() + "\n" + "RSI : "
				+ rsiIv.toString() + "\n" + "fast ema : " + fastEma.toString() + "\n" + "slow ema : "
				+ slowEma.toString() + "\n" + "macd : " + macdIv.toString() + "\n" + "macd signal : "
				+ macdSignalIv.toString() + "\n" + "200 ema : " + ema200.toString();
		MailSender.sendMail(Constants.TO_MAIL, Constants.TO_NAME, Constants.MACD_RSI_STRATEGY_SETUP_DATA, mailbody,
				mailAccount);

	}

	@Override
	public void simulation(Long security) {
		List<Candle> cList = null;
		Calendar prevDayCalFrom = Calendar.getInstance();
		prevDayCalFrom.setTime(new Date());
		prevDayCalFrom.set(Calendar.HOUR_OF_DAY, 9);
		prevDayCalFrom.set(Calendar.MINUTE, 15);
		prevDayCalFrom.set(Calendar.SECOND, 0);
		Date from = prevDayCalFrom.getTime();
		Date to = new Date();
		cList = getPrevDayCandles(security, IntervalType.MINUTE_1, from, to, 200);
		for (Candle c : cList) {
			processor.getTradeCall(c);
		}
	}

	@Override
	public Map<String, Long> getTokenNameMap() {
		Map<String, Long> tokenNameMap = new HashMap<>();
		List<Object[]> nameTokenList = tradeRepo.runNativeQuery(TradeQueryBuilder.nativeQueryToFetchNameTokenMap());
		if (nameTokenList != null && !nameTokenList.isEmpty()) {
			for (Object[] nt : nameTokenList) {
				tokenNameMap.put(nt[0].toString(), Long.parseLong(nt[1].toString()));
			}
		}
		return tokenNameMap;

	}

}
