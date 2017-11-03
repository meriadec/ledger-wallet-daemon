package co.ledger.wallet.daemon.models

import java.util.UUID

import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext
import co.ledger.wallet.daemon.database.PoolDto
import co.ledger.wallet.daemon.models.Account.Account
import djinni.NativeLibLoader
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

class WalletTest extends AssertionsForJUnit {
  NativeLibLoader.loadLibs()
  implicit val ec: ExecutionContext = MDCPropagatingExecutionContext.Implicits.global

  private val PUBKEYS = List[String](
    "0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901",
    "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b")
  private val CHAINCODES = List[String](
    "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71",
    "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb")

  private val testPool = Pool.newInstance(Await.result(Pool.newCoreInstance(new PoolDto(UUID.randomUUID().toString, 2L, "", Option(0L))), Duration.Inf), 1L)

  private val testWallet = Await.result(testPool.addWalletIfNotExit("test_wallet", "bitcoin"), Duration.Inf)

  private val testAccount: Account = Await.result(testWallet.accounts().flatMap { as =>
    assert(as.isEmpty)
    testWallet.accountCreationInfo(None).map { derivation =>
      AccountDerivationView(
        derivation.index,
        derivation.view.derivations.zipWithIndex.map { d =>
          DerivationView(d._1.path, d._1.owner, Option(PUBKEYS(d._2)), Option(CHAINCODES(d._2)))
        }
      )
    }.flatMap { info =>
      testWallet.addAccountIfNotExit(info) }
  }, Duration.Inf)

  @Test def verifyWalletActivities(): Unit = {
    val accounts = Await.result(testWallet.accounts(), Duration.Inf)
    assert(1 === accounts.size)
    assert(testAccount === accounts.head)
    val account = Await.result(testWallet.account(testAccount.accountIndex), Duration.Inf)
    assert(account === accounts.headOption)
    val walletView = Await.result(testWallet.walletView, Duration.Inf)
    val accountView = Await.result(testAccount.accountView, Duration.Inf)
    assert(walletView.balance === accountView.balance)
    testPool.stopRealTimeObserver()
  }

//  @Test def verifyOperations(): Unit = {
//    Await.result(testAccount.operation("nonexistoperation", 1), Duration.Inf)
//  }
}
