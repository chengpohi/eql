package com.github.chengpohi.dsl.eql

import java.io.{BufferedWriter, FileWriter}
import java.nio.file.Paths

import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryRequestBuilder
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequestBuilder
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequestBuilder
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequestBuilder
import org.elasticsearch.action.search.{
  SearchRequestBuilder,
  SearchScrollRequestBuilder
}

import scala.collection.JavaConverters._
import scala.concurrent.Future

trait QueryEQL extends EQLDefinition with IndexEQL {

  case object get {
    def repository(repositoryName: String): PutRepositoryDefinition = {
      val putRepository: PutRepositoryRequestBuilder =
        clusterClient.preparePutRepository(repositoryName)
      PutRepositoryDefinition(putRepository)
    }

    def snapshot(snapshotName: String): GetSnapshotDefinition = {
      GetSnapshotDefinition(snapshotName)
    }

    def mapping(indexName: String): GetMappingDefinition = {
      val mappingsRequestBuilder: GetMappingsRequestBuilder =
        indicesClient.prepareGetMappings(indexName)
      GetMappingDefinition(mappingsRequestBuilder)
    }

    def settings(indexName: String): GetSettingsRequestDefinition = {
      val getSettingsRequestBuilder: GetSettingsRequestBuilder =
        indicesClient.prepareGetSettings(indexName)
      GetSettingsRequestDefinition(getSettingsRequestBuilder)
    }
  }

  case object search {
    def in(indexName: String): SearchRequestDefinition = {
      val searchRequestBuilder: SearchRequestBuilder =
        client.prepareSearch(indexName)
      SearchRequestDefinition(searchRequestBuilder)
    }

    def in(indexPath: IndexPath): SearchRequestDefinition = {
      val searchRequestBuilder: SearchRequestBuilder =
        indexPath.indexType match {
          case "*" => client.prepareSearch(indexPath.indexName)
          case _ =>
            client
              .prepareSearch(indexPath.indexName)
              .setTypes(indexPath.indexType)
        }
      SearchRequestDefinition(searchRequestBuilder)
    }

    def scroll(s: String): SearchScrollRequestDefinition = {
      val searchScrollRequestBuilder: SearchScrollRequestBuilder =
        client.prepareSearchScroll(s)
      SearchScrollRequestDefinition(searchScrollRequestBuilder)
    }
  }

  case object bulk {
    def update(indexPath: IndexPath): BulkUpdateRequestDefinition = {
      BulkUpdateRequestDefinition(indexPath)
    }

    def index(indexPath: IndexPath): BulkIndexRequestDefinition = {
      BulkIndexRequestDefinition(indexPath)
    }
  }

  case object update {
    def id(documentId: String): UpdateRequestDefinition = {
      UpdateRequestDefinition(documentId)
    }

    def index(indexPath: IndexPath): PutMappingRequestDefinition = {
      val putMappingRequestBuilder: PutMappingRequestBuilder = indicesClient
        .preparePutMapping(indexPath.indexName)
        .setType(indexPath.indexType)
      PutMappingRequestDefinition(putMappingRequestBuilder)
    }
  }

  case object dump {

    def index(indexName: String): DumpIndexRequestDefinition = {
      DumpIndexRequestDefinition(indexName)
    }
  }

  case object reindex {
    def into(indexPath: IndexPath): ReindexRequestDefinition = {
      ReindexRequestDefinition(indexPath)
    }
  }

  case class ReindexRequestDefinition(indexPath: IndexPath)
      extends Definition[String] {
    var _sourceIndex: String = _
    var _fields: List[String] = _

    def from(_si: String): ReindexRequestDefinition = {
      _sourceIndex = _si
      this
    }

    def fields(_f: List[String]): ReindexRequestDefinition = {
      _fields = _f
      this
    }

    override def execute: Future[String] = {
      val searchResponse = EQL {
        search in _sourceIndex size MAX_RETRIEVE_SIZE scroll "10m"
      }
      searchResponse.map(response => {
        response
          .filter(s =>
            s.getType == indexPath.indexType || indexPath.indexType == "*")
          .foreach(s => {
            EQL {
              index into indexPath.indexName / s.getType doc s.getSourceAsMap.asScala
                .filter(i => _fields.contains(i._1))
                .toMap
            }
          })
        "success"
      })
    }

    override def json: String = execute.toJson
  }

  case class BulkUpdateRequestDefinition(indexPath: IndexPath)
      extends Definition[String] {
    var _fields: Map[String, String] = _

    def fields(_uf: List[(String, String)]): BulkUpdateRequestDefinition = {
      _fields = _uf.toMap
      this
    }

    def fields(_uf: Map[String, String]): BulkUpdateRequestDefinition = {
      _fields = _uf.toMap
      this
    }

    override def execute: Future[String] = {
      val searchResponse = EQL {
        search in indexPath size MAX_RETRIEVE_SIZE scroll "10m"
      }
      searchResponse.map(f => {
        f.filter(s =>
            s.getType == indexPath.indexName || indexPath.indexType == "*")
          .map { s =>
            EQL {
              update id s.getId doc _fields in indexPath
            }
          }
        "success"
      })
    }

    override def json: String = execute.toJson
  }

  case class BulkIndexRequestDefinition(indexPath: IndexPath)
      extends Definition[String] {
    var _fields: List[List[(String, String)]] = _

    def doc(_f: List[List[(String, String)]]): BulkIndexRequestDefinition = {
      _fields = _f
      this
    }

    override def execute: Future[String] = {
      val res = _fields.map(f => {
        EQL {
          index into indexPath fields f
        }
      })
      Future
        .sequence(res)
        .map(f => {
          "success"
        })
    }

    override def json: String = execute.toJson
  }

  case class DumpIndexRequestDefinition(indexName: String)
      extends Definition[String] {
    var _fileName: String = _

    def into(file: String): DumpIndexRequestDefinition = {
      _fileName = file
      this
    }

    override def execute: Future[String] = {
      val path = Paths.get(_fileName)
      val writer: BufferedWriter = new BufferedWriter(new FileWriter(_fileName))
      val searchResponse = EQL {
        search in indexName query "*" size 20 scroll "10m"
      }
      searchResponse.map(j => {
        j.map(i => {
            s"""index into "${i.getIndex}" / "${i.getType}" doc ${i.getSourceAsString} id "${i.getId}" """
          })
          .foreach(l => {
            writer.write(l + System.lineSeparator())
          })
        writer.flush()
        writer.close()
        path.toUri.toString
      })
    }

    override def json: String = execute.await.json
  }

}
