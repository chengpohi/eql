package com.github.chengpohi.base

import com.github.chengpohi.connector.ElasticClientConnector
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.SearchType.Scan
import com.sksamuel.elastic4s.source.{JsonDocumentSource, DocumentMap}
import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest

import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.search.sort.SortOrder.ASC


import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * ElasticBase function
 * Created by chengpohi on 6/28/15.
 */
class ElasticBase {
  lazy val client = ElasticClientConnector.client

  def deleteIndex(indexName: String): Unit = {
    client.execute {
      delete index indexName
    }.await
  }

  def deleteIndexType(indexName: String, indexType: String): Unit = {
    client.execute {
      delete mapping indexName / indexType
    }.await
  }

  def deleteById(documentId: String, indexName: String, indexType: String): Boolean = client.execute {
    delete id documentId from s"${indexName}/${indexType}"
  }.await.isFound

  def createIndex(indexName: String) = client.execute {
    create index indexName
  }

  def indexMap(indexName: String, indexType: String, docuemntMap: DocumentMap): String = {
    val resp = client.execute {
      index into indexName / indexType doc docuemntMap
    }.await
    resp.getId
  }

  def indexField(indexName: String, indexType: String, uf: (String, String)): Future[IndexResponse] = client.execute {
    index into indexName / indexType fields uf
  }

  def indexFieldById(indexName: String, indexType: String, uf: (String, String), docId: String): Future[IndexResponse] = client.execute {
    index into indexName / indexType fields uf id docId
  }

  def indexJSON(indexName: String, indexType: String, json: String): String = {
    val resp = client.execute {
      index into indexName / indexType doc new JsonDocumentSource(json)
    }.await
    resp.getId
  }

  def indexMapById(indexName: String, indexType: String, specifyId: String, documentMap: DocumentMap): IndexResponse = client.execute {
    index into indexName / indexType doc documentMap id specifyId
  }.await

  def getAllDataByIndexTypeWithIndexName(indexName: String, indexType: String): SearchResponse = client.execute {
    search in indexName / indexType query filteredQuery postFilter matchAllFilter start 0 limit Integer.MAX_VALUE sort (
      by field "created_at" ignoreUnmapped true order ASC
      )
  }.await

  def getAllDataByIndexType(indexType: String): SearchResponse = client.execute {
    search in "*" / indexType query filteredQuery postFilter matchAllFilter start 0 limit Integer.MAX_VALUE
  }.await

  def getAllDataByIndexName(indexName: String): SearchResponse = client.execute {
    search in indexName query filteredQuery postFilter matchAllFilter start 0 limit Integer.MAX_VALUE
  }.await

  def getAllDataByScan(indexName: String, indexType: Option[String] = Some("*")): Stream[SearchResponse] = {
    val res = client.execute {
      search in indexName searchType Scan scroll "10m" size 500
    }

    def fetch(previous: SearchResponse) = {
      client.execute {
        search scroll previous.getScrollId
      }
    }

    def toStream(current: Future[SearchResponse]): Stream[SearchResponse] = {
      val result = Await.result(current, Duration.Inf)
      result.getScrollId match {
        case null => result #:: Stream.empty
        case _ => result #:: toStream(fetch(result))
      }
    }
    toStream(res)
  }

  case class MapSource(source: Map[String, AnyRef]) extends DocumentMap {
    override def map = source
  }

  def bulkCopyIndex(indexName: String, response: Stream[SearchResponse], indexType: String, fields: Array[String]) = {
    response.foreach(r => {
      client.execute {
        bulk(
          r.getHits.getHits.filter(s => s.getType == indexType || indexType == "*").map {
            s => index into indexName / s.getType doc MapSource(s.getSource.asScala.filter(i => fields.contains(i._1)).toMap)
          }: _*
        )
      }
    })
  }

  def bulkUpdateField(indexName: String, response: Stream[SearchResponse], indexType: String, field: (String, String)) = {
    response.foreach(r => {
      client.execute {
        bulk(
          r.getHits.getHits.filter(s => s.getType == indexType || indexType == "*").map {
            s => update(s.getId).in(s"$indexName/$indexType").doc(field)
          }: _*
        )
      }
    })
  }

  def analysis(analyzer: String, text: String) = {
    val analyzeResponse = Future {
      val request = new AnalyzeRequest(text)
      request.analyzer(analyzer)
      client.admin.indices().analyze(request).get()
    }
    analyzeResponse
  }

  def getDocById(indexName: String, indexType: String, docId: String) = client.execute {
    get id docId from s"$indexName/$indexType"
  }
}
