#!/bin/sh
# Initialize the repository contents
# Note: Create Elasticseach description index first.
curl -v -X DELETE \
    http://localhost:9200/descriptions

curl -v -X PUT \
  -H "Content-Type: application/json; charset=utf-8" \
  -H "Accept: application/json" \
  --data @- \
  http://localhost:9200/descriptions << EOF
{
  "settings": {
    "analysis": {
      "analyzer": {
        "descriptive_path": {
          "tokenizer": "custom_hierarchy"
        }
      },
      "tokenizer": {
        "custom_hierarchy": {
          "type": "path_hierarchy",
          "delimiter": "/"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "pathfacet": {
        "type": "text",
        "fielddata": "true",
        "analyzer": "descriptive_path"
      },
      "path": {
          "type": "keyword"
      },
      "title": {
          "type": "text"
      },
      "description": {
          "type": "text"
      },
      "level": {
          "type": "keyword"
      },
      "depth": {
          "type": "short"
      }
    }
  }
}
EOF

curl -v -X POST \
    -H "Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"" \
    -H "Slug: submissions" \
    http://localhost:9090

curl -v -X POST \
    -H "Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"" \
    -H "Slug: description" \
    http://localhost:9090

curl -v -X POST \
    -H "Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"" \
    -H "Slug: name-authority" \
    http://localhost:9090

curl -v -X POST \
    -H "Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"" \
    -H "Slug: person" \
    http://localhost:9090/name-authority/

curl -v -X POST \
    -H "Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"" \
    -H "Slug: organization" \
    http://localhost:9090/name-authority/

curl -v -X POST \
    -H "Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"" \
    -H "Slug: place" \
    http://localhost:9090/name-authority/

curl -v -X PUT --data "@docker_stack_vols/config/SKOS_Samples_list_rev_20220902.json" \
     -H "Link: <http://www.w3.org/ns/ldp#RDFSource>; rel=\"type\"" \
     -H "Content-Type: application/ld+json" \
     http://localhost:9090/name-authority/person/LOC_SKOS_Subset.json
