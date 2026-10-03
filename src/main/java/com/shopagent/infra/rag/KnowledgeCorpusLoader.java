package com.shopagent.infra.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * knowledge/*.md 语料加载与解析。
 * 格式契约（语料是 fixture，宁要死板的确定性不要花哨的灵活性）：
 *  - 「# 」一级标题 = 文件主题，不入索引
 *  - 首个条目前的「key: value」行 = 文件级 metadata（现仅 productId）
 *  - 「## 」二级标题 = 单条知识条目（<500 字整条入索引，不做切分——切分策略服务规模，§2.2）
 *    标题前缀决定 docType：「FAQ：」→ faq、「规格：」→ spec，其余缺省 faq
 *  - source 由文件名推断：policy-*.md → policy，否则 product
 * 条目内容 = 标题（去前缀）+ 正文：用户提问与问句语义最相近，问句必须一起进向量。
 */
public final class KnowledgeCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusLoader.class);

    private static final String CLASSPATH_PATTERN = "classpath:knowledge/*.md";
    private static final String DOC_TYPE_FAQ_PREFIX = "FAQ";
    private static final String DOC_TYPE_SPEC_PREFIX = "规格";
    private static final String META_SEPARATOR = "---";
    private static final Pattern FILE_META_LINE = Pattern.compile("^([A-Za-z]+):\\s*(.+)$");
    private static final String HEX_ID_LENGTH = "20";

    private KnowledgeCorpusLoader() {
    }

    /** 全量加载 classpath 语料。列表按文档 id 排序——classpath 列举顺序不保证稳定，指纹必须跨次一致。 */
    public static List<Document> load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(CLASSPATH_PATTERN);
            List<Document> docs = new ArrayList<>();
            for (Resource resource : resources) {
                docs.addAll(parse(resource.getFilename(),
                        resource.getContentAsString(StandardCharsets.UTF_8)));
            }
            docs.sort(Comparator.comparing(Document::getId));
            log.info("知识语料加载完成：{} 个文件，{} 条", resources.length, docs.size());
            return docs;
        } catch (IOException e) {
            throw new IllegalStateException("知识语料读取失败", e);
        }
    }

    /** 单文件解析。fileName 参与 docId 派生，调用方须保证同名文件内容稳定。 */
    public static List<Document> parse(String fileName, String content) {
        String source = fileName.startsWith("policy") ? "policy" : "product";
        List<Document> docs = new ArrayList<>();
        Long productId = null;
        String heading = null;
        StringBuilder body = new StringBuilder();

        for (String line : content.split("\r?\n")) {
            if (heading == null) {
                if (line.startsWith("## ")) {
                    heading = line.substring(3).trim();
                } else if (!line.startsWith("#")) {
                    Matcher meta = FILE_META_LINE.matcher(line.trim());
                    if (meta.matches() && "productId".equals(meta.group(1))) {
                        productId = Long.valueOf(meta.group(2).trim());
                    }
                }
            } else if (line.startsWith("## ")) {
                docs.add(toDocument(fileName, source, productId, heading, body.toString()));
                heading = line.substring(3).trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        if (heading != null) {
            docs.add(toDocument(fileName, source, productId, heading, body.toString()));
        }
        return docs;
    }

    /** 语料全集指纹：id + 内容 + metadata 排序拼接后 sha256。任何一条变更 = 指纹变 = 触发全量重建。 */
    public static String fingerprint(List<Document> docs) {
        List<Document> sorted = new ArrayList<>(docs);
        sorted.sort(Comparator.comparing(Document::getId));
        StringBuilder canonical = new StringBuilder();
        for (Document doc : sorted) {
            Map<String, Object> metadata = new TreeMap<>(doc.getMetadata());
            canonical.append(doc.getId()).append('\n')
                    .append(doc.getText()).append('\n')
                    .append(metadata).append("\n").append(META_SEPARATOR).append('\n');
        }
        return sha256Hex(canonical.toString());
    }

    private static Document toDocument(String fileName, String source, Long productId,
                                       String rawHeading, String body) {
        String docType = "faq";
        String heading = rawHeading;
        if (rawHeading.startsWith(DOC_TYPE_FAQ_PREFIX)) {
            heading = stripPrefix(rawHeading, DOC_TYPE_FAQ_PREFIX);
        } else if (rawHeading.startsWith(DOC_TYPE_SPEC_PREFIX)) {
            docType = "spec";
            heading = stripPrefix(rawHeading, DOC_TYPE_SPEC_PREFIX);
        }
        String text = heading + "\n" + body.trim();
        Document.Builder builder = Document.builder()
                .id(sha256Hex(fileName + "::" + heading).substring(0, Integer.parseInt(HEX_ID_LENGTH)))
                .text(text)
                .metadata("source", source)
                .metadata("docType", docType);
        if (productId != null) {
            builder.metadata("productId", productId);
        }
        return builder.build();
    }

    private static String stripPrefix(String heading, String prefix) {
        String stripped = heading.substring(prefix.length());
        return stripped.startsWith("：") || stripped.startsWith(":")
                ? stripped.substring(1).trim() : stripped.trim();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
