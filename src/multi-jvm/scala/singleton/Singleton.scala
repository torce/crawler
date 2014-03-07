package singleton


/**
 * User: david
 * Date: 14/02/14
 * Time: 16:18
 */

object SingletonMultiJvmMaster {
  def main(args: Array[String]) {
    /*System.setProperty("akka.remote.netty.tcp.port", "2551")
    val system = ActorSystem("ClusterSystem", ConfigFactory.load())
    val master = initMaster(system)
    val crawler = initCrawler(system)
    val downloader = initDownloader(system)
    val manager =  initManager(system, master, downloader, crawler)  */
  }
}

object SingletonMultiJvmSeed {
  def main(args: Array[String]) {
    /*System.setProperty("akka.remote.netty.tcp.port", "2552")
    val system = ActorSystem("ClusterSystem", ConfigFactory.load())
    val master = initMaster(system)
    val crawler = initCrawler(system)
    val downloader = initDownloader(system)
    val manager =  initManager(system, master, downloader, crawler) */
  }
}