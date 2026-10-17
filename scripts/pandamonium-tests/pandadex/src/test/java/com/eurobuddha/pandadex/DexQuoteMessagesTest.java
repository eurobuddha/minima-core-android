package com.eurobuddha.pandadex;

import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DexQuoteMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception {return (Object[])field(value,"arguments");}
    private static int resource(Object value)throws Exception {return (Integer)field(value,"resource");}
    private static List<Object> lines(Object display,int id)throws Exception {
        List<Object> out=new ArrayList<>();for(Object v:args(display))if(v instanceof Language.Message && resource(v)==id)out.add(v);return out;
    }
    private static Object line(Object display,int id)throws Exception {List<Object> found=lines(display,id);assertEquals("Expected one resource "+id,1,found.size());return found.get(0);}
    private static Object fixture(Class<?> cls,String name,Class<?>[] types,Object... values)throws Exception {
        Method m=cls.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,values);
    }
    private static Order5 sell(String id,String locked,String want,String minimum)throws Exception {
        return (Order5)fixture(SweepPlannerTest.class,"sell",new Class[]{String.class,String.class,String.class,String.class},id,locked,want,minimum);
    }
    private static Order5 order(boolean sell,String minima,String price)throws Exception {
        return (Order5)fixture(CompositeRouterTest.class,"order",new Class[]{String.class,boolean.class,String.class,String.class},"0xC1",sell,minima,price);
    }
    private static Pool pool()throws Exception {return (Pool)fixture(CompositeRouterTest.class,"pool",new Class[]{String.class,String.class},"1000","10");}
    @Test public void actualSweepKeepsBestPriceOrderPartialAmountAndSnapshot()throws Exception {
        SweepPlanner.Plan p=SweepPlanner.plan(Arrays.asList(sell("0xC1","100","0.60","1"),sell("0xC2","100","0.50","1")),true,new BigDecimal("150"),null,200);
        assertEquals("0xC2",p.takes.get(0).order.coinid);assertTrue(p.takes.get(1).partial);
        Object display=MainActivity.sweepMessage(p,true,BigDecimal.ZERO,new BigDecimal("0.006"));
        assertArrayEquals(new Object[]{"150","2"},args(line(display,R.string.l10n_dex_sweep_buying)));
        assertEquals("0.8",args(line(display,R.string.l10n_dex_quote_average))[1]);
        assertArrayEquals(new Object[]{"50","0.006000"},args(line(display,R.string.l10n_dex_quote_partial)));
        assertTrue(Arrays.asList(args(display)).contains("  • 100 @ 0.005000"));
        assertTrue(lines(display,R.string.l10n_dex_quote_rest).isEmpty());assertEquals(2,p.takes.size());
        p.totalMinima=new BigDecimal("999");assertEquals("150",args(line(display,R.string.l10n_dex_sweep_buying))[0]);
    }
    @Test public void minimumRemainderProducesTheSameRestingOrder()throws Exception {
        SweepPlanner.Plan p=SweepPlanner.plan(Collections.singletonList(sell("0xC1","100","0.50","20")),true,new BigDecimal("95"),null,200);
        assertEquals(0,new BigDecimal("80").compareTo(p.totalMinima));
        Object display=MainActivity.sweepMessage(p,true,new BigDecimal("95").subtract(p.totalMinima),new BigDecimal("0.005"));
        assertArrayEquals(new Object[]{"80","1"},args(line(display,R.string.l10n_dex_sweep_buying)));
        assertArrayEquals(new Object[]{"80","0.005000"},args(line(display,R.string.l10n_dex_quote_partial)));
        assertArrayEquals(new Object[]{"15","0.005000"},args(line(display,R.string.l10n_dex_quote_rest)));
        assertEquals(0,new BigDecimal("20").compareTo(p.takes.get(0).order.minRem));
    }
    @Test public void mixedBuyAndSellQuotesKeepEachLiquiditySourceAndTokenDirection()throws Exception {
        for(boolean buy:new boolean[]{true,false}) {
            Pool pool=pool();CompositeRouter.Plan p=CompositeRouter.plan(Collections.singletonList(order(buy,"20",buy?"0.0103":"0.0097")),Collections.singletonList(pool),buy,new BigDecimal("80"),new BigDecimal(buy?"0.0200":"0.0010"),200);
            assertTrue(p.orderMinima.signum()>0);assertTrue(p.poolMinima.signum()>0);
            Set<String> sources=new LinkedHashSet<>(p.sourceCoinIds);BigDecimal total=p.totalMinima,usdt=p.totalUsdt;
            Object display=MainActivity.compositeMessage(p,buy,BigDecimal.ZERO,BigDecimal.ONE);
            assertArrayEquals(new Object[]{PriceMath.fmt(total)},args(line(display,buy?R.string.l10n_dex_quote_buying:R.string.l10n_dex_quote_selling)));
            assertArrayEquals(new Object[]{PriceMath.fmtPrice(p.effectivePrice),PriceMath.fmt(usdt)},args(line(display,R.string.l10n_dex_quote_effective)));
            assertArrayEquals(new Object[]{PriceMath.fmt(p.orderMinima),PriceMath.fmt(p.orderUsdt),String.valueOf(p.orderTakes.size())},args(line(display,R.string.l10n_dex_quote_book)));
            assertArrayEquals(new Object[]{PriceMath.fmt(p.poolMinima),PriceMath.fmt(p.poolUsdt),String.valueOf(p.poolCount())},args(line(display,R.string.l10n_dex_quote_pool)));
            List<Object> legs=lines(display,R.string.l10n_dex_quote_pool_leg);assertEquals(p.poolRoute.allocs.size(),legs.size());
            for(int i=0;i<legs.size();i++) {
                PoolRouter.Alloc a=p.poolRoute.allocs.get(i);
                assertArrayEquals(new Object[]{PriceMath.fmt(buy?a.quote.outAmount:a.quote.inAmount),PriceMath.fmt(buy?a.quote.inAmount:a.quote.outAmount)},args(legs.get(i)));
            }
            assertEquals(sources,p.sourceCoinIds);assertEquals(total,p.totalMinima);assertEquals(usdt,p.totalUsdt);
            line(display,R.string.l10n_dex_quote_impact);line(display,R.string.l10n_dex_quote_worst);
        }
    }
    @Test public void singleSourceQuotesDoNotInventOtherLiquidity()throws Exception {
        CompositeRouter.Plan book=CompositeRouter.plan(Collections.singletonList(order(true,"100","0.005")),Collections.emptyList(),true,new BigDecimal("40"),new BigDecimal("0.006"),200);
        Object display=MainActivity.compositeMessage(book,true,BigDecimal.ZERO,new BigDecimal("0.006"));
        line(display,R.string.l10n_dex_quote_book);line(display,R.string.l10n_dex_quote_book_partial);
        assertTrue(lines(display,R.string.l10n_dex_quote_pool).isEmpty());assertTrue(lines(display,R.string.l10n_dex_quote_impact).isEmpty());
        CompositeRouter.Plan pools=CompositeRouter.plan(Collections.emptyList(),Collections.singletonList(pool()),true,new BigDecimal("10"),new BigDecimal("0.020"),200);
        display=MainActivity.compositeMessage(pools,true,BigDecimal.ZERO,new BigDecimal("0.020"));
        line(display,R.string.l10n_dex_quote_pool);assertTrue(lines(display,R.string.l10n_dex_quote_book).isEmpty());
    }
    @Test public void quoteBindingPreservesSeparatorsAndUnknownLines()throws Exception {
        String unknown="literal 0x"+"ab".repeat(64)+" 中文 Русский Українська {value} $1";
        Object display=DexMessages.quote("Buying 00012.34000 MINIMA now\r\n\n"+unknown+"\n");
        Object[] parts=args(display);assertArrayEquals(new Object[]{"00012.34000"},args(parts[0]));
        assertArrayEquals(new Object[]{"\r","\n","","\n",unknown,"\n",""},Arrays.copyOfRange(parts,1,parts.length));
        assertEquals("",DexMessages.quote(null));assertEquals("",DexMessages.quote(""));
    }
    @Test public void receiptFailuresStayUncertainAndUnknownDiagnosticsRemainWhole()throws Exception {
        assertEquals(R.string.l10n_dex_trade_unknown,resource(DexMessages.message("Trade outcome unknown — receipt retained. Review ASSETS.")));
        assertEquals(R.string.l10n_dex_trade_receipt_failed,resource(DexMessages.message("Trade receipt could not be saved safely · pending evidence retained; trading remains paused")));
        for(String side:new String[]{"buy","sell"}) {
            Object posted=DexMessages.message("Posted — waiting for a block to confirm your "+side+" of 00012.34000 MINIMA");
            assertEquals("buy".equals(side)?R.string.l10n_dex_posted_buy:R.string.l10n_dex_posted_sell,resource(posted));
            assertArrayEquals(new Object[]{"00012.34000"},args(posted));
        }
        String unknown="node payload\nBuying 100 MINIMA now\n0x"+"ff".repeat(64);
        Object failure=DexMessages.message("Trade failed — "+unknown);
        assertEquals(R.string.l10n_dex_trade_failure,resource(failure));assertArrayEquals(new Object[]{unknown},args(failure));
    }
}
