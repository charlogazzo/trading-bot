Backtest Trading Bot

This repository contains a small backtesting tool that can load historical bars from CSV or the Alpaca Data API and run TA4J-based backtests (baseline and risk-aware modes).

CLI usage
---------

Run the backtester with:

Note: this project depends on external libraries (TA4J, SLF4J, etc.). When running
with the `java` command you must place those dependency JARs on the runtime
classpath. During development the easiest options are shown below.

1) Run via Maven (dependencies handled automatically):

```bash
mvn exec:java -Dexec.mainClass="com.foreshock.tradingbot.BacktestHourly" \
	-Dexec.args="--mode BASELINE|RISK|WFT [options]"
```

2) Copy dependencies and run with `java` (explicit classpath):

```bash
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
java -cp target/classes:target/dependency/* com.foreshock.tradingbot.BacktestHourly --mode BASELINE|RISK|WFT [options]
```


Common options
--------------

- `--source CSV|ALPACA`  Data source (default: `ALPACA`)
- `--symbol AAPL`        Ticker symbol (default: `AAPL`)
- `--start yyyy-MM-dd`   Start date (UTC midnight) (default: `2024-01-02`)
- `--end yyyy-MM-dd`     End date (UTC midnight)   (default: `2024-06-30`)
- `--timeframe <tf>`     Bar timeframe (default: `1Hour`) — see accepted formats below

Accepted timeframe strings
--------------------------
The loader supports the same timeframe strings used by the Alpaca Data API. Valid formats are:

- `[1-59]Min`  — minute bars, e.g. `1Min`, `5Min`, `15Min`, `30Min`
- `[1-24]Hour` — hourly bars, e.g. `1Hour`, `4Hour`, `12Hour`
- `1Day`       — daily bars
- `1Week`      — weekly bars
- `1Month`, `2Month`, `3Month`, `4Month`, `6Month`, `12Month` — monthly bars (allowed month values: 1,2,3,4,6,12)

Notes
-----
- When loading CSV resources, the timestamp format should match `yyyy-MM-dd'T'HH:mm` (minute precision) so bars can be constructed correctly for non-hourly timeframes.
- Internally months are approximated as 30 days when converting to a `Duration` for TA4J `BaseBar`. If you need calendar-accurate month lengths, editing the loader to use `Period`-aware logic is recommended.
- Default timeframe when omitted is `1Hour`.

Examples
--------

Load hourly bars from Alpaca and run the risk backtest:

```bash
java -cp target/classes com.foreshock.tradingbot.BacktestHourly --mode RISK --symbol AAPL --timeframe 1Hour --start 2024-01-01 --end 2024-06-01
```

Load 5-minute bars from a CSV resource:

```bash
java -cp target/classes com.foreshock.tradingbot.BacktestHourly --mode BASELINE --source CSV --timeframe 5Min
```

Contributing
------------
PRs welcome. If you change timeframe handling for months to be calendar-accurate, please add unit tests that confirm month-length behavior.
