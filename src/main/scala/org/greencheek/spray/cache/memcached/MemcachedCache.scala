package org.greencheek.spray.cache.memcached

import spray.caching.Cache
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import net.spy.memcached._
import java.net.InetSocketAddress
import org.slf4j.{LoggerFactory, Logger}
import java.util.concurrent.{TimeoutException, TimeUnit}
import net.spy.memcached.ConnectionFactoryBuilder.{Protocol, Locator}
import net.spy.memcached.transcoders.Transcoder
import org.greencheek.spy.extensions.FastSerializingTranscoder
import scala.collection.JavaConversions._
import org.greencheek.spray.cache.memcached.keyhashing._
import org.greencheek.spy.extensions.connection.CustomConnectionFactoryBuilder
import org.greencheek.spy.extensions.hashing.{JenkinsHash => JenkinsHashAlgo, AsciiXXHashAlogrithm, XXHashAlogrithm}
import org.greencheek.spray.cache.memcached.hostparsing.{CommaSeparatedHostAndPortStringParser, HostStringParser}
import org.greencheek.spray.cache.memcached.hostparsing.dnslookup.{AddressByNameHostResolver, HostResolver}
import org.greencheek.spray.cache.memcached.hostparsing.connectionchecking.{TCPHostValidation, HostValidation}
import net.spy.memcached.internal.CheckedOperationTimeoutException

/*
 * Created by dominictootell on 26/03/2014.
 */
object MemcachedCache {
  private val DEFAULT_EXPIRY : Duration = Duration(60,TimeUnit.MINUTES)
  private val DEFAULT_CAPACITY = 1000
  private val ONE_SECOND = Duration(1,TimeUnit.SECONDS)

  val XXHASH_ALGORITHM: HashAlgorithm = new XXHashAlogrithm
  val JENKINS_ALGORITHM: HashAlgorithm = new JenkinsHashAlgo
  val DEFAULT_ALGORITHM: HashAlgorithm = DefaultHashAlgorithm.KETAMA_HASH

  val CACHE_TYPE_VALUE_CALCULATION : String = "value_calculation_cache"
  val CACHE_TYPE_CACHE_DISABLED : String = "disabled_cache"
  val CACHE_TYPE_STALE_CACHE : String = "stale_distributed_cache"
  val CACHE_TYPE_DISTRIBUTED_CACHE : String = "distributed_cache"

}


class MemcachedCache[Serializable](val timeToLive: Duration = MemcachedCache.DEFAULT_EXPIRY,
                                   val maxCapacity: Int = MemcachedCache.DEFAULT_CAPACITY,
                                   val memcachedHosts : String = "localhost:11211",
                                   val hashingType : Locator = Locator.CONSISTENT,
                                   val failureMode : FailureMode = FailureMode.Redistribute,
                                   val hashAlgorithm : HashAlgorithm = DefaultHashAlgorithm.KETAMA_HASH,
                                   val serializingTranscoder : Transcoder[Object] = new FastSerializingTranscoder(),
                                   val protocol : ConnectionFactoryBuilder.Protocol = Protocol.BINARY,
                                   val readBufferSize : Int = DefaultConnectionFactory.DEFAULT_READ_BUFFER_SIZE,
                                   val memcachedGetTimeout : Duration = Duration(2500,TimeUnit.MILLISECONDS),
                                   val throwExceptionOnNoHosts : Boolean = false,
                                   val dnsConnectionTimeout : Duration = Duration(3,TimeUnit.SECONDS),
                                   val doHostConnectionAttempt : Boolean = false,
                                   val hostConnectionAttemptTimeout : Duration = MemcachedCache.ONE_SECOND,
                                   val waitForMemcachedSet : Boolean = false,
                                   val setWaitDuration : Duration = Duration(2,TimeUnit.SECONDS),
                                   val allowFlush : Boolean = false,
                                   val waitForMemcachedRemove : Boolean = false,
                                   val removeWaitDuration : Duration = Duration(2,TimeUnit.SECONDS),
                                   val keyHashType : KeyHashType = NoKeyHash,
                                   val keyPrefix : Option[String] = None,
                                   val asciiOnlyKeys : Boolean = false,
                                   val hostStringParser : HostStringParser = CommaSeparatedHostAndPortStringParser,
                                   val hostHostResolver : HostResolver = AddressByNameHostResolver,
                                   val hostValidation : HostValidation = TCPHostValidation,
                                   val useStaleCache : Boolean = false,
                                   val staleCacheAdditionalTimeToLive : Duration = Duration.MinusInf,
                                   val staleCachePrefix  : String = "stale",
                                   val staleMaxCapacity : Int = -1,
                                   val staleCacheMemachedGetTimeout : Duration = Duration.MinusInf,
                                   val memcachedGetHerdProtectionMaxCapacity : Int = -1)
  extends Cache[Serializable] {

  private val hashKeyPrefix = keyPrefix match {
    case None => false
    case Some(_ : String) => true
  }

  private val keyprefix = keyPrefix match {
    case None => ""
    case Some(prefix) => prefix
  }

  private val client : Option[MemcachedClientIF] = createMemcachedClient(memcachedHosts)
  private val isEnabled : Boolean = client.isDefined
  private val memcached : MemcachedClientIF = if(isEnabled) { client.get } else { null }


  private val logger  : Logger = LoggerFactory.getLogger(classOf[MemcachedCache[Serializable]])
  private val cacheHitMissLogger  : Logger  = LoggerFactory.getLogger("MemcachedCacheHitsLogger")

  require(maxCapacity >= 0, "maxCapacity must not be negative")

  private[cache] val store = new ConcurrentLinkedHashMap.Builder[String, Future[Serializable]]
    .initialCapacity(maxCapacity)
    .maximumWeightedCapacity(maxCapacity)
    .build()

  private val staleMaxCapacityValue : Int = if (staleMaxCapacity == -1) maxCapacity else staleMaxCapacityValue
  private val staleCacheAdditionalTimeToLiveValue : Duration = if (staleCacheAdditionalTimeToLive == Duration.MinusInf) timeToLive else staleCacheAdditionalTimeToLive

  private[cache] val staleStore = new ConcurrentLinkedHashMap.Builder[String, Future[Serializable]]
    .initialCapacity(staleMaxCapacityValue)
    .maximumWeightedCapacity(staleMaxCapacityValue)
    .build()

  private val memcachedGetHerdProtectionMaxCapacityValue : Int = if (memcachedGetHerdProtectionMaxCapacity == -1) maxCapacity else memcachedGetHerdProtectionMaxCapacity


  private[cache] val herdGetStore = new ConcurrentLinkedHashMap.Builder[String, Future[Serializable]]
    .initialCapacity(memcachedGetHerdProtectionMaxCapacityValue)
    .maximumWeightedCapacity(memcachedGetHerdProtectionMaxCapacityValue)
    .build()

  private val keyHashingFunction : KeyHashing = keyHashType match {
    case MD5KeyHash | MD5UpperKeyHash => createMd5KeyHasher(asciiOnlyKeys,true)
    case SHA256KeyHash | SHA256UpperKeyHash => createShaKeyHasher(asciiOnlyKeys,true)
    case MD5LowerKeyHash => createMd5KeyHasher(asciiOnlyKeys,false)
    case SHA256LowerKeyHash => createShaKeyHasher(asciiOnlyKeys,false)
    case NoKeyHash => NoKeyHashing.INSTANCE
    case XXJavaHash => createXXKeyHasher(asciiOnlyKeys,false)
    case XXNativeJavaHash => createXXKeyHasher(asciiOnlyKeys,true)
    case JenkinsHash => JenkinsKeyHashing
    case _ => NoKeyHashing.INSTANCE
  }

  private val setWaitDurationInMillis = setWaitDuration.toMillis
  private val memcachedGetTimeoutMillis = memcachedGetTimeout.toMillis
  private val staleCacheMemachedGetTimeoutMillis = staleCacheMemachedGetTimeout match {
    case Duration.MinusInf => memcachedGetTimeoutMillis
    case _ => staleCacheMemachedGetTimeout.toMillis
  }


  private def createMemcachedClient(memcachedHosts : String) : Option[MemcachedClient] = {
    val parsedHosts : List[(String,Int)] =  hostStringParser.parseMemcachedNodeList(memcachedHosts)

    parsedHosts match {
      case Nil => {
        if(throwExceptionOnNoHosts) {
          throw new InstantiationError()
        } else {
         None
        }
      }
      case hosts : List[(String,Int)] => {
        var addresses : List[InetSocketAddress] = hostHostResolver.returnSocketAddressesForHostNames(parsedHosts,dnsConnectionTimeout)
        addresses match {
          case Nil => {
            if(throwExceptionOnNoHosts) {
              throw new InstantiationError()
            } else {
              None
            }
          }
          case resolvedHosts : List[InetSocketAddress] => {
            if(doHostConnectionAttempt) {
              addresses = hostValidation.validateMemcacheHosts(hostConnectionAttemptTimeout,resolvedHosts)
            }
          }
        }

        addresses match {
          case Nil => {
            None
          }
          case hostsToUse : List[InetSocketAddress] => {
            val builder: ConnectionFactoryBuilder = keyValidationRequired(keyHashType) match {
              case true => new ConnectionFactoryBuilder();
              case false => new CustomConnectionFactoryBuilder();
            }
            builder.setHashAlg(createHashAlgorithm(asciiOnlyKeys,hashAlgorithm))
            builder.setLocatorType(hashingType)
            builder.setProtocol(protocol)
            builder.setReadBufferSize(readBufferSize)
            builder.setFailureMode(failureMode)
            builder.setTranscoder(serializingTranscoder)

            Some(new MemcachedClient(builder.build(),hostsToUse))
          }
        }
      }
    }
  }

  private def createHashAlgorithm(asciiOnlyKeys : Boolean, hashAlgorithm : HashAlgorithm) : HashAlgorithm = {
    hashAlgorithm match {
      case algo : XXHashAlogrithm => {
        if(asciiOnlyKeys) {
          new AsciiXXHashAlogrithm()
        } else {
          algo
        }
      }
      case algo : HashAlgorithm  => algo
    }
  }

  private def keyValidationRequired(keyHashType : KeyHashType ) : Boolean = {
    keyHashType match {
      case MD5KeyHash | MD5UpperKeyHash | MD5LowerKeyHash => false
      case SHA256KeyHash | SHA256UpperKeyHash | SHA256LowerKeyHash => false
      case XXJavaHash | XXNativeJavaHash => false
      case JenkinsHash => false
      case NoKeyHash => true
      case _ => true
    }
  }



  private def createXXKeyHasher(asciiOnlyKeys : Boolean, native : Boolean) : KeyHashing = {
    if(asciiOnlyKeys) {
      if(native) {
        AsciiXXKeyHashing.JNI_INSTANCE
      } else {
        AsciiXXKeyHashing.JAVA_INSTANCE
      }
    } else {
      if(native) {
        XXKeyHashing.JNI_INSTANCE
      } else {
        XXKeyHashing.JAVA_INSTANCE
      }
    }
  }

  private def createShaKeyHasher(asciiOnlyKeys : Boolean, uppercase : Boolean) : KeyHashing = {
    if (asciiOnlyKeys) {
      new AsciiSHA256DigestKeyHashing(upperCase = false)
    } else {
      new SHA256DigestKeyHashing(upperCase = false)
    }
  }

  private def createMd5KeyHasher(asciiOnlyKeys : Boolean, uppercase : Boolean) : KeyHashing = {
    if (asciiOnlyKeys) {
      new AsciiMD5DigestKeyHashing(upperCase = uppercase)
    } else {
      new MD5DigestKeyHashing(upperCase = uppercase)
    }
  }

  private def getHashedKey(key : String) : String = {
    if(hashKeyPrefix) {
      keyHashingFunction.hashKey(keyprefix + key)
    } else {
      keyHashingFunction.hashKey(key)
    }
  }

  private def logCacheHit(key: Any, cacheType: Any): Unit = {
    cacheHitMissLogger.debug("{ \"cachehit\" : \"{}\", \"cachetype\" : \"{}\"}",key,cacheType)
  }

  private def logCacheMiss(key: Any, cacheType: Any): Unit = {
    cacheHitMissLogger.debug("{ \"cachemiss\" : \"{}\", \"cachetype\" : \"{}\"}",key,cacheType)
  }

  private def getFromDistributedCache(key: String): Option[Future[Serializable]] = {
    try {
        val future =  memcached.asyncGet(key)
        val cacheVal = future.get(memcachedGetTimeoutMillis,TimeUnit.MILLISECONDS)
        if(cacheVal==null){
            logCacheMiss(key,MemcachedCache.CACHE_TYPE_DISTRIBUTED_CACHE)
            None
        } else {
            logCacheHit(key,MemcachedCache.CACHE_TYPE_DISTRIBUTED_CACHE)
            Some(Future.successful(cacheVal.asInstanceOf[Serializable]))
        }
    } catch {
      case e @ (_ : OperationTimeoutException | _ :  CheckedOperationTimeoutException | _ : TimeoutException) => {
        logger.error("timeout when retrieving key {} from memcached",key)
        None
      }
      case e: Exception => {
        logger.error("Unable to contact memcached", e)
        None
      }
      case e: Throwable => {
        logger.error("Exception thrown when communicating with memcached", e)
        None
      }
    }
  }

  private def getDuration(timeToLive : Duration) : Long = {
    if(timeToLive == Duration.Inf) {
      0
    }
    else if(timeToLive == null) {
      0
    } else {
      val timeToLiveSec : Long = timeToLive.toSeconds
      if(timeToLiveSec >= 1l) {
        timeToLiveSec
      } else {
        0
      }
    }


  }

  private def writeToDistributedCache(key: String, value : Serializable,
                                      timeToLive : Duration, waitForMemcachedSet : Boolean) : Unit = {
    val entryTTL : Long = getDuration(timeToLive)

    if( waitForMemcachedSet ) {
      val futureSet = memcached.set(key, entryTTL.toInt, value)
      try {
        futureSet.get(setWaitDurationInMillis, TimeUnit.MILLISECONDS)
      } catch {
        case e: Exception => {
          logger.warn("Exception waiting for memcached set to occur",e)
        }
      }
    } else {
      try {
        memcached.set(key, entryTTL.toInt, value)
      } catch {
        case e: Exception => {
          logger.warn("Exception waiting for memcached set to occur")
        }
      }
    }
  }

  def get(key: Any): Option[Future[Serializable]] = {
    val keyString : String = getHashedKey(key.toString)
    if(!isEnabled) {
      logCacheMiss(keyString,MemcachedCache.CACHE_TYPE_CACHE_DISABLED)
      None
    } else {
      val future : Future[Serializable] = store.get(keyString)
      if(future==null) {
        val getProtectuFuture :  Future[Serializable]  = herdGetStore.get(keyString)
        if(getProtectuFuture==null) {
          getFromDistributedCache(keyString)
        } else {
          Some(getProtectuFuture)
        }
      }
      else {
        logCacheHit(keyString,MemcachedCache.CACHE_TYPE_VALUE_CALCULATION)
        Some(getFutueForStaleDistributedCacheLookup(keyString,future,null))
      }
    }
  }


  override def apply(key: Any,genValue: () => Future[Serializable])(implicit ec: ExecutionContext): Future[Serializable] = {
    if(key.isInstanceOf[Tuple2[_,_]]) {
      key match {
        case x: (_, _) if x._1.isInstanceOf[Duration] => {
          apply(x._1.asInstanceOf[Duration], x._2, genValue)
        }
        case x: (_, _) if x._2.isInstanceOf[Duration] => {
          apply(x._2.asInstanceOf[Duration], x._1, genValue)
        }
        case _ => {
          apply(timeToLive, key, genValue)
        }
      }
    } else {
      apply(timeToLive, key, genValue)
    }
  }

  def apply(itemExpiry : Duration, key : Any, genValue: () => Future[Serializable])(implicit ec: ExecutionContext): Future[Serializable] = {
    val keyToString : String = key.toString
    val keyString = getHashedKey(keyToString)

    var staleCacheKey : String = null
    var staleCacheExpiry : Duration = null;
    if(useStaleCache) {
      staleCacheKey = createStaleCacheKey(keyString)
      staleCacheExpiry = itemExpiry.plus(staleCacheAdditionalTimeToLiveValue)
    }

    if(!isEnabled) {
      logCacheMiss(keyString,MemcachedCache.CACHE_TYPE_CACHE_DISABLED)
      genValue()
    }
    else {
      // check local whilst computation is occurring cache.
      val existingFuture : Future[Serializable] = store.get(keyString)
      if(existingFuture==null) {
        // check memcached.
        val getHerdStoreFuture : Future[Serializable] = herdGetStore.get(keyString)
        if(getHerdStoreFuture == null) {
          val getPromise = Promise[Serializable]()

          val alreadyStoredGetHerdFuture = herdGetStore.putIfAbsent(keyString,getPromise.future)

          if(alreadyStoredGetHerdFuture==null) {
            Future {
              val future : Future[Serializable] = getFromDistributedCache(keyString).getOrElse {
                // create and store a new future for the to be generated value
                val promise = Promise[Serializable]()
                val alreadyStoredFuture: Future[Serializable] = store.putIfAbsent(keyString, promise.future)
                if (alreadyStoredFuture == null) {
                  logger.debug("set requested for {}", keyString)
                  cacheWriteFunction(genValue(), promise, keyString, staleCacheKey, itemExpiry, staleCacheExpiry, ec)
                } else {
                  if (useStaleCache) {
                    getFutueForStaleDistributedCacheLookup(staleCacheKey, alreadyStoredFuture, ec)
                  } else {
                    alreadyStoredFuture
                  }
                }
              }
              future
            }.onComplete { value =>
              herdGetStore.remove(keyString,getPromise.future)
              getPromise.completeWith(value.get)
            }
            getPromise.future
          }
          else {
            alreadyStoredGetHerdFuture
          }
        }
        else {
          getHerdStoreFuture
        }
      }
      else  {
        if(useStaleCache) {
          getFutueForStaleDistributedCacheLookup(staleCacheKey,existingFuture,ec)
        } else {
          logCacheHit(keyString,MemcachedCache.CACHE_TYPE_VALUE_CALCULATION)
          existingFuture
        }
      }
    }
  }

  private def createStaleCacheKey(key : String) : String = {
    staleCachePrefix + key
  }

  /**
   * Talks to memcached to find a cached entry. If the entry does not exist, the backend Future will
   * be 'consulted' and it's value with be returned.
   *
   * @param key The cache key to lookup
   * @param promise the promise on which requests are waiting.
   * @param backendFuture the future that is running the long returning calculation that creates a fresh entry.
   */
  private def getFromStaleDistributedCache(key : String,
                                           promise : Promise[Serializable],
                                           backendFuture : Future[Serializable]) : Unit = {
    try {
      val future = memcached.asyncGet(key)
      val cacheVal = future.get(staleCacheMemachedGetTimeoutMillis, TimeUnit.MILLISECONDS)
      if (cacheVal == null) {
        logCacheMiss(key,MemcachedCache.CACHE_TYPE_STALE_CACHE)
        promise.completeWith(backendFuture)
      } else {
        logCacheHit(key,MemcachedCache.CACHE_TYPE_STALE_CACHE)
        promise.success(cacheVal.asInstanceOf[Serializable])
      }
    } catch {
      case e @ (_ : OperationTimeoutException | _ :  CheckedOperationTimeoutException | _ : TimeoutException) => {
        logger.error("timeout when retrieving key {} from memcached", key)
        promise.completeWith(backendFuture)
      }
      case e: Exception => {
        logger.error("Unable to contact memcached", e)
        promise.completeWith(backendFuture)
      }
      case e: Throwable => {
        logger.error("Exception thrown when communicating with memcached", e)
        promise.completeWith(backendFuture)
      }
    } finally {
      staleStore.remove(key, promise.future)
    }
  }

  /**
   * returns a future that is consulting the stale memcached cache.  If the item is not in the
   * cache, the backendFuture will be invoked (complete the operation).
   *
   * @param key The stale cache key
   * @param backendFuture The future that is actually calculating the fresh cache entry
   * @param ec  The require execution context to run the stale cache key.
   * @return  A future that will result in the stored Serializable object
   */
  private def getFutueForStaleDistributedCacheLookup(key : String, backendFuture : Future[Serializable],
                                                     ec: ExecutionContext) : Future[Serializable] = {

    // protection against thundering herd on stale memcached
    val existingFuture : Future[Serializable] = staleStore.get(key)

    if(existingFuture == null) {
      val promise = Promise[Serializable]()
      val alreadyStoredFuture : Future[Serializable] = staleStore.putIfAbsent(key, promise.future)
      if(alreadyStoredFuture==null) {
        if(ec!=null) {
          Future {
            getFromStaleDistributedCache(key, promise, backendFuture)
          }(ec)
        }
        else {
          getFromStaleDistributedCache(key, promise, backendFuture)
        }
        promise.future
      }
      else {
        alreadyStoredFuture
      }
    }
    else {
      existingFuture
    }
  }


  /**
   * write to memcached when the future completes, the generated value,
   * against the given key, with the specified expiry
   * @param future  The future that will generate the value
   * @param promise The promise that is stored in the thurdering herd local cache
   * @param key The key against which to store an item
   * @param itemExpiry the expiry for the item
   * @return
   */
  private def cacheWriteFunction(future : Future[Serializable],promise: Promise[Serializable],
                                 key : String, staleCacheKey : String, itemExpiry : Duration,
                                 staleItemExpiry : Duration,
                                 ec: ExecutionContext) : Future[Serializable] = {
    future.onComplete {
      value =>
        // Need to check memcached exception here
        try {
          if (!value.isFailure) {
            val entryValue : Serializable = value.get

            if(useStaleCache) {
              // overwrite the stale cache entry
              writeToDistributedCache(staleCacheKey, entryValue, staleItemExpiry, false)
            }
            // write the cache entry
            writeToDistributedCache(key,entryValue,itemExpiry,waitForMemcachedSet)
          }
        } catch {
          case e: Exception => {
            logger.error("problem setting key {} in memcached",key)
          }
        } finally {

          try {
            promise.complete(value)
          } catch {
            case e: IllegalStateException => {
              logger.error("future already completed for key {}",key)
            }
          } finally {
            // it is an LRU cache, so the value may have been thrown out.
            store.remove(key, promise.future)
          }
        }
    }(ec)
    promise.future
  }

  def remove(key: Any) = {
    if(!isEnabled) {
      None
    }
    else {
      val keyString: String = getHashedKey(key.toString)
      val removedFuture: Option[Future[Serializable]] = store.remove(keyString) match {
        case null => None
        case x => Some(x)
      }

      val memcachedRemovedFuture = getFromDistributedCache(keyString) match {
        case None => {
          None
        }
        case future => {
          try {
            if(useStaleCache) memcached.delete(createStaleCacheKey(keyString))

            if(waitForMemcachedRemove) {
              val futureRemove = memcached.delete(keyString)
              try {
                futureRemove.get(removeWaitDuration.toMillis, TimeUnit.MILLISECONDS)
              } catch {
                case e: Exception => {
                  logger.warn("Exception waiting for memcached remove to occur")
                }
              }
            } else {
              memcached.delete(keyString)
            }
          } catch {
            case e: Exception => {
              logger.error("exception removing item {} from memcached", keyString)
            }
          }
          future
        }
      }

      removedFuture match {
        case None => {
          memcachedRemovedFuture
        }
        case Some(_) => {
          removedFuture
        }
      }
    }
  }

  def clear(): Unit = {
    if ( allowFlush ) {
      try {
        store.clear()
        staleStore.clear()
        memcached.flush()
      } catch {
        case e : Exception => {
          logger.error("Exception encountered when attempting to flush memcached")
        }
      }
    } else {
      throw new UnsupportedOperationException
    }
  }

  def size = {
    if(!isEnabled) {
      0
    }
    else {
      0
    }

  }

  def close()= {
    if(isEnabled) {
      store.clear()
      staleStore.clear()
      memcached.shutdown()
    }

  }

  def isCacheEnabled() : Boolean = {
    isEnabled
  }

}
