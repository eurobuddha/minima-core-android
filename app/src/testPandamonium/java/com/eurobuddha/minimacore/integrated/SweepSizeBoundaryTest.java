package com.eurobuddha.minimacore.integrated;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.minima.database.MinimaDB;
import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.objects.TxPoW;
import org.minima.objects.base.MiniNumber;
import org.minima.system.Main;
import org.minima.system.brains.TxPoWGenerator;
import org.minima.system.commands.CommandException;
import org.minima.system.commands.txn.txnpost;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Executes txnpost from the actual bundled minima.jar; mocked chain and miner, no broadcast. */
public class SweepSizeBoundaryTest {
    @Test public void bundledNodeRejectsOversizedSweepBeforeAccessingMiner()throws Exception{
        MinimaDB db=mock(MinimaDB.class,RETURNS_DEEP_STUBS);TxnRow row=mock(TxnRow.class,RETURNS_DEEP_STUBS);TxPoW proof=mock(TxPoW.class);
        when(db.getCustomTxnDB().getTransactionRow("sweep_fixture")).thenReturn(row);
        when(db.getTxPoWTree().getTip().getTxPoW().getMagic().getMaxTxPoWSize()).thenReturn(new MiniNumber(65536));
        when(proof.getSizeinBytesWithoutBlockTxns()).thenReturn(71234L);
        try(MockedStatic<MinimaDB> database=mockStatic(MinimaDB.class);MockedStatic<TxPoWGenerator> generator=mockStatic(TxPoWGenerator.class);MockedStatic<Main> main=mockStatic(Main.class)){
            database.when(MinimaDB::getDB).thenReturn(db);
            generator.when(()->TxPoWGenerator.generateTxPoW(row.getTransaction(),row.getWitness())).thenReturn(proof);
            for(boolean sync:new boolean[]{true,false}){
                CommandException refusal=assertThrows(CommandException.class,()->txnpost.postTxn("sweep_fixture",MiniNumber.ZERO,false,sync));
                assertEquals("TxPoW size too large.. 71234/65536",refusal.getMessage());
            }
            main.verifyNoInteractions();
        }
    }
}
