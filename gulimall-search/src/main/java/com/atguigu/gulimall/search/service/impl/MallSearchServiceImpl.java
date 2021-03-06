package com.atguigu.gulimall.search.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.to.es.SkuEsModel;
import com.atguigu.common.utils.R;
import com.atguigu.gulimall.search.config.GulimallElasticSearchConfig;
import com.atguigu.gulimall.search.constant.EsConstant;
import com.atguigu.gulimall.search.feign.ProductFeignService;
import com.atguigu.gulimall.search.service.MallSearchService;
import com.atguigu.gulimall.search.vo.AttrResponseVo;
import com.atguigu.gulimall.search.vo.BrandVo;
import com.atguigu.gulimall.search.vo.SearchParam;
import com.atguigu.gulimall.search.vo.SearchResult;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.nested.NestedAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.nested.ParsedNested;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedLongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description TODO
 * @Author ??????????????????
 * @Date 2020/4/14 11:22
 * @Version 1.0
 **/
@Service
public class MallSearchServiceImpl implements MallSearchService {

    @Autowired
    private RestHighLevelClient client;

    @Autowired
    private ProductFeignService productFeignService;

    /**
     * ???es?????????????????????
     * @param param ?????????????????????
     * @return
     */
    @Override
    public SearchResult search(SearchParam param) {

        SearchResult result = null;

        // 1?????????????????????
        SearchRequest searchRequest = buildSearchRequest(param);

        try {
            // 2?????????????????????
            SearchResponse response = client.search(searchRequest, GulimallElasticSearchConfig.COMMON_OPTIONS);

            // 3??????????????????????????????????????????????????????
            result = buildSearchResult(response, param);

        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    /**
     * 1?????????????????????
     * # ???????????? ?????????????????????????????????????????????????????????????????? ?????? ?????? ????????????
     * @return
     */
    private SearchRequest buildSearchRequest(SearchParam param) {

        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        /*  ???????????? ???????????????????????????????????????????????????????????? */
        // 1????????? bool - query
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        // 1.1???must - query
        if (!StringUtils.isEmpty(param.getKeyword())) {
            boolQuery.must(QueryBuilders.matchQuery("skuTitle", param.getKeyword()));
        }

        /* ========================================= 1.2????????? bool - filter (s)  ========================================= */
        // ??? ?????????????????? catalogId - filter
        if (param.getCatalog3Id() != null) {
            boolQuery.filter(QueryBuilders.termQuery("catalogId", param.getCatalog3Id()));
        }
        // ??? ?????????????????? brandId - filter
        if (param.getBrandId() != null && param.getBrandId().size() > 0) {
            boolQuery.filter(QueryBuilders.termsQuery("brandId", param.getBrandId()));
        }
        // ??? ?????????????????? attrs - filter
        if (param.getAttrs() != null && param.getAttrs().size() > 0) {

            for (String attrStr : param.getAttrs()) {
                // attrs=1_5???:8???&attrs=2_8G:16G
                BoolQueryBuilder nestedBoolQuery = QueryBuilders.boolQuery();

                String[] str = attrStr.split("_");
                String attrId = str[0]; // ???????????????id
                String[] attrValue = str[1].split(":"); // ?????????????????????
                nestedBoolQuery.must(QueryBuilders.termQuery("attrs.attrId", attrId));
                nestedBoolQuery.must(QueryBuilders.termsQuery("attrs.attrValue", attrValue));

                NestedQueryBuilder nestedQuery = QueryBuilders.nestedQuery("attrs", nestedBoolQuery, ScoreMode.None); // ScoreMode.None ???????????????

                boolQuery.filter(nestedQuery);
            }

        }

        // ??? ?????????????????? hasStock - filter
        if (param.getHasStock() != null) {
            boolQuery.filter(QueryBuilders.termQuery("hasStock", param.getHasStock() == 1));
        }

        // ??? ?????????????????? skuPrice - filter
        if (!StringUtils.isEmpty(param.getSkuPrice())) {
            // skuPrice -> 1_500/_500/500_
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("skuPrice");

            String[] str = param.getSkuPrice().split("_");
            if (str.length == 2) { // skuPrice -> 1_500
                rangeQuery.gte(str[0]).lte(str[1]);
            } else if (str.length == 1) {
                if (param.getSkuPrice().startsWith("_")) { // skuPrice -> _500
                    rangeQuery.lte(str[0]);
                }
                if (param.getSkuPrice().endsWith("_")) { // skuPrice -> 500_
                    rangeQuery.gte(str[0]);
                }
            }
            boolQuery.filter(rangeQuery);
        }

        /* ========================================= 1.2????????? bool - filter (e)  ========================================= */
        sourceBuilder.query(boolQuery);


        /*  ?????? ?????? ??????  */
        // 2.1????????? sort
        //  saleCount_asc/desc  skuPrice_asc/desc hotScore_asc/desc
        if (!StringUtils.isEmpty(param.getSort())) {
            String sortStr = param.getSort();
            String[] strs = sortStr.split("_");
            SortOrder order = strs[1].equalsIgnoreCase("asc") ? SortOrder.ASC : SortOrder.DESC;
            sourceBuilder.sort(strs[0], order);
        }
        // 2.2?????????
        if (param.getPageNum() != null) {
            sourceBuilder.from((param.getPageNum() - 1) * EsConstant.PRODUCT_PAGESIZE); // from = (pageNum - 1) * pageSize
            sourceBuilder.size(EsConstant.PRODUCT_PAGESIZE);
        }
        // 2.3?????????
        if (!StringUtils.isEmpty(param.getKeyword())) {
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            highlightBuilder.field("skuTitle");
            highlightBuilder.preTags("<b style='color:red'>");
            highlightBuilder.postTags("</b>");
            sourceBuilder.highlighter(highlightBuilder);
        }
        /* ========================================= 3????????????????????? (s)  ========================================= */

        // ??? brand_agg ????????????
        TermsAggregationBuilder brand_agg = AggregationBuilders.terms("brand_agg"); // ?????????
        brand_agg.field("brandId").size(50); // ?????????????????? ????????????
        // brand_agg ??????????????????????????? brand_name_agg ???????????????
        brand_agg.subAggregation(AggregationBuilders.terms("brand_name_agg").field("brandName").size(1));
        // brand_agg ??????????????????????????? brand_img_agg ??????????????????
        brand_agg.subAggregation(AggregationBuilders.terms("brand_img_agg").field("brandImg").size(1));

        sourceBuilder.aggregation(brand_agg);

        // ??? catalog_agg ????????????
        TermsAggregationBuilder catalog_agg = AggregationBuilders.terms("catalog_agg").field("catalogId").size(20);
        // catalog_agg ??????????????????????????? catalog_name_agg ??????????????????
        catalog_agg.subAggregation(AggregationBuilders.terms("catalog_name_agg").field("catalogName").size(1));

        sourceBuilder.aggregation(catalog_agg);

        // ??? attr_agg ???????????????
        NestedAggregationBuilder attr_agg = AggregationBuilders.nested("attr_agg", "attrs");
        // attr_id_agg ??????id?????? ????????????????????????attrId
        TermsAggregationBuilder attr_id_agg = AggregationBuilders.terms("attr_id_agg").field("attrs.attrId");
        // attr_name_agg ??????????????? ---> attr_id_agg ??????id?????????????????????
        attr_id_agg.subAggregation(AggregationBuilders.terms("attr_name_agg").field("attrs.attrName").size(1));
        // attr_value_agg ??????????????? ---> attr_id_agg ??????id?????????????????????
        attr_id_agg.subAggregation(AggregationBuilders.terms("attr_value_agg").field("attrs.attrValue").size(50));

        attr_agg.subAggregation(attr_id_agg);

        sourceBuilder.aggregation(attr_agg);
        /* ========================================= 3????????????????????? (e)  ========================================= */


        SearchRequest searchRequest = new SearchRequest(new String[]{EsConstant.PRODUCT_INDEX}, sourceBuilder);

        String s = sourceBuilder.toString();
        System.out.println("?????????DSL" + s);
        return searchRequest;
    }

    /**
     * 3??????????????????????????????????????????????????????
     * @param response
     * @return
     */
    private SearchResult buildSearchResult(SearchResponse response, SearchParam param) {
        SearchResult result = new SearchResult();

        // 1?????????????????????
        SearchHits hits = response.getHits();
        List<SkuEsModel> esModels = new ArrayList<>();
        if (hits.getHits() != null && hits.getHits().length > 0) {
            for (SearchHit hit : hits.getHits()) {
                String sourceAsString = hit.getSourceAsString();
                SkuEsModel skuEsModel = JSON.parseObject(sourceAsString, SkuEsModel.class);
                if (!StringUtils.isEmpty(param.getKeyword())) { // ????????????????????????  ????????????????????????skuTitle
                    HighlightField skuTitleHighlightField = hit.getHighlightFields().get("skuTitle");
                    String skuTitle = skuTitleHighlightField.getFragments()[0].string();
                    skuEsModel.setSkuTitle(skuTitle);
                }
                esModels.add(skuEsModel);
            }
        }

        result.setProducts(esModels);

        /* ========================================= 2????????????????????? (s)  ========================================= */
        // ??? ??????????????????
        List<SearchResult.BrandVo> brandVos = new ArrayList<>();
        ParsedLongTerms brand_agg = response.getAggregations().get("brand_agg");
        List<? extends Terms.Bucket> brand_buckets = brand_agg.getBuckets();
        for (Terms.Bucket bucket : brand_buckets) {
            SearchResult.BrandVo brandVo = new SearchResult.BrandVo();
            // ???????????????id
            long brandId = bucket.getKeyAsNumber().longValue();
            // ?????????????????????????????? ??????????????????
            String brandImg = ((ParsedStringTerms) bucket.getAggregations().get("brand_img_agg")).getBuckets().get(0).getKeyAsString();
            // ?????????????????????????????? ??????????????????
            String brandName = ((ParsedStringTerms) bucket.getAggregations().get("brand_name_agg")).getBuckets().get(0).getKeyAsString();

            brandVo.setBrandId(brandId);
            brandVo.setBrandImg(brandImg);
            brandVo.setBrandName(brandName);

            brandVos.add(brandVo);
        }
        result.setBrands(brandVos);

        // ??? ??????????????????
        ParsedLongTerms catalog_agg = response.getAggregations().get("catalog_agg");
        List<SearchResult.CatalogVo> catalogVos = new ArrayList<>();
        List<? extends Terms.Bucket> buckets = catalog_agg.getBuckets();
        for (Terms.Bucket bucket : buckets) {
            SearchResult.CatalogVo catalogVo = new SearchResult.CatalogVo();

            String keyAsString = bucket.getKeyAsString();

            catalogVo.setCatalogId(Long.parseLong(keyAsString));

            // ???????????????????????? catalog_name_agg
            ParsedStringTerms catalog_name_agg = bucket.getAggregations().get("catalog_name_agg");
            String catalog_name = catalog_name_agg.getBuckets().get(0).getKeyAsString();
            catalogVo.setCatalogName(catalog_name);

            catalogVos.add(catalogVo);
        }
        result.setCatalogs(catalogVos);

        // ??? ??????????????????
        List<SearchResult.AttrVo> attrVos = new ArrayList<>();
        ParsedNested attr_agg = response.getAggregations().get("attr_agg");
        ParsedLongTerms attr_id_agg = attr_agg.getAggregations().get("attr_id_agg");
        for (Terms.Bucket bucket : attr_id_agg.getBuckets()) {
            SearchResult.AttrVo attrVo = new SearchResult.AttrVo();
            // ???????????????id
            long attrId = bucket.getKeyAsNumber().longValue();
            // ?????????????????????
            String attrName = ((ParsedStringTerms) bucket.getAggregations().get("attr_name_agg")).getBuckets().get(0).getKeyAsString();
            // ????????????????????????
            List<String> attrValues = ((ParsedStringTerms) bucket.getAggregations().get("attr_value_agg")).getBuckets().stream().map(item -> {

                String keyAsString = ((Terms.Bucket) item).getKeyAsString();
                return keyAsString;
            }).collect(Collectors.toList());

            attrVo.setAttrId(attrId);
            attrVo.setAttrName(attrName);
            attrVo.setAttrValue(attrValues);

            attrVos.add(attrVo);

        }

        result.setAttrs(attrVos);

        /* ========================================= 2????????????????????? (e)  ========================================= */



        result.setPageNum(param.getPageNum());

        long total = hits.getTotalHits().value;
        result.setTotal(total);

        int totalPages = (int) total % EsConstant.PRODUCT_PAGESIZE == 0 ? (int) total / EsConstant.PRODUCT_PAGESIZE : ((int) total / EsConstant.PRODUCT_PAGESIZE + 1);
        result.setTotalPages(totalPages);

        // ???????????????????????????
        List<Integer> pageNavs = new ArrayList<>();
        for (int i = 1; i <= totalPages; i++) {
            pageNavs.add(i);
        }
        result.setPageNavs(pageNavs);


        // ???????????????????????????
        if (param.getAttrs() != null && param.getAttrs().size() > 0) {
            List<SearchResult.NavVo> navVos = param.getAttrs().stream().map(item -> {
                SearchResult.NavVo navVo = new SearchResult.NavVo();
                String[] s = item.split("_");
                navVo.setNavValue(s[1]);
                R r = productFeignService.info(Long.parseLong(s[0]));

                // ???????????????????????????????????????????????????id - ?????????????????????????????????????????????id
                result.getAttrIds().add(Long.parseLong(s[0]));

                if (r.getCode() == 0) {
                    AttrResponseVo responseVo = r.getData("attr", new TypeReference<AttrResponseVo>() {});
                    navVo.setNavName(responseVo.getAttrName());
                } else {
                    navVo.setNavName(s[0]);
                }

                // ????????????????????????????????????????????????????????????url??????
                String replace = replaceQueryString(param, item, "attrs");
                navVo.setLink("http://search.gulimall.com/list.html?"+replace);
                return navVo;
            }).collect(Collectors.toList());

            result.setNavs(navVos);
        }

        // ???????????????????????????????????????
        if (param.getBrandId() != null && param.getBrandId().size() > 0) {
            List<SearchResult.NavVo> navs = result.getNavs();

            SearchResult.NavVo navVo = new SearchResult.NavVo();
            navVo.setNavName("??????");
            R r = productFeignService.brandsInfo(param.getBrandId());
            if (r.getCode() == 0) {
                List<BrandVo> brands = r.getData("brands", new TypeReference<List<BrandVo>>() {});
                StringBuffer buffer = new StringBuffer();
                String replace = null;
                for (BrandVo brand : brands) {
                    buffer.append(brand.getName()+";");

                    replace = replaceQueryString(param, brand.getBrandId() + "", "brandId");
                }
                navVo.setNavValue(buffer.toString());

                navVo.setLink("http://search.gulimall.com/list.html?" + replace);

            }
            navs.add(navVo);

        }


        return result;
    }

    private String replaceQueryString(SearchParam param, String value, String key) {
        String encode = null;
        try {
            encode = URLEncoder.encode(value, "UTF-8");
            encode = encode.replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return param.get_queryString().replace("&" + key + "=" + encode, "");
    }
}
