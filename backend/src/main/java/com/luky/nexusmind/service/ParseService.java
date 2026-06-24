package com.luky.nexusmind.service;

import com.luky.nexusmind.model.DocumentVector;
import com.luky.nexusmind.model.DocumentContentFormat;
import com.luky.nexusmind.model.ParseEngine;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private AiTraceService aiTraceService;

    @Autowired
    private MinerUParseClient minerUParseClient;

    @Autowired
    private FileProcessingStatusService processingStatusService;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.min-chunk-size:256}")
    private int minChunkSize;

    @Value("${file.parsing.max-chunk-size:4096}")
    private int maxChunkSize;

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;
    
    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;
    
    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    @Value("${file.parsing.mineru.fallback-to-tika:true}")
    private boolean minerUFallbackToTika;
    
    public ParseService() {
        // 无需初始化，StandardTokenizer是静态方法
    }

    /**
     * 以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
     * 采用"父文档-子切片"策略。
     *
     * @param fileMd5    文件的MD5哈希值，用于唯一标识文件
     * @param fileStream 文件输入流，用于读取文件内容
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public int parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        return parseAndSave(fileMd5, fileStream, userId, orgTag, isPublic, ParseEngine.TIKA, null);
    }

    public int parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic, ParseEngine requestedEngine, String fileName) throws IOException, TikaException {
        return parseAndSave(fileMd5, fileStream, userId, orgTag, isPublic, requestedEngine, fileName, null);
    }

    public int parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic, ParseEngine requestedEngine, String fileName, Integer requestedChunkSize) throws IOException, TikaException {
        int effectiveChunkSize = resolveChunkSize(requestedChunkSize);
        ParseEngine engine = resolveEngine(requestedEngine, fileName);
        if (engine == ParseEngine.MINERU) {
            return parseWithMinerUAndSave(fileMd5, fileStream, userId, orgTag, isPublic, requestedEngine, fileName, effectiveChunkSize);
        }
        return parseWithTikaAndSave(fileMd5, fileStream, userId, orgTag, isPublic, effectiveChunkSize);
    }

    private int parseWithTikaAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic, int effectiveChunkSize) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);

        AiTraceService.TraceSpan span = aiTraceService.startFileSpan("file.parse.tika", userId, fileMd5, null)
                .attribute("nexusmind.org_tag", orgTag)
                .attribute("nexusmind.upload.is_public", isPublic)
                .attribute("nexusmind.parse.chunk_size", effectiveChunkSize)
                .attribute("nexusmind.parse.parent_chunk_size", parentChunkSize);
        try {
        documentVectorRepository.deleteByFileMd5(fileMd5);
        checkMemoryThreshold();
        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            // 创建一个流式处理器，它会在内部处理父块的切分和子块的保存
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic, effectiveChunkSize);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            // Tika的parse方法会驱动整个流式处理过程
            // 当handler的characters方法接收到足够数据时，会触发分块、切片和保存
            parser.parse(bufferedStream, handler, metadata, context);

            span.attribute("nexusmind.parse.saved_chunks", handler.getSavedChunkCount());
            processingStatusService.markActualParseEngine(fileMd5, userId, ParseEngine.TIKA);
            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);
            return handler.getSavedChunkCount();

        } catch (SAXException e) {
            span.error(e);
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
        } catch (IOException | TikaException | RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
            span.close();
        }
    }

    private int parseWithMinerUAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic, ParseEngine requestedEngine, String fileName, int effectiveChunkSize) throws IOException, TikaException {
        logger.info("开始使用MinerU解析文件，fileMd5: {}, fileName: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, fileName, userId, orgTag, isPublic);

        byte[] fileBytes = fileStream.readAllBytes();
        AiTraceService.TraceSpan span = aiTraceService.startFileSpan("file.parse.mineru", userId, fileMd5, fileName)
                .attribute("nexusmind.org_tag", orgTag)
                .attribute("nexusmind.upload.is_public", isPublic)
                .attribute("nexusmind.parse.chunk_size", effectiveChunkSize)
                .attribute("nexusmind.parse.parent_chunk_size", parentChunkSize)
                .attribute("nexusmind.parse.requested_engine", requestedEngine != null ? requestedEngine.name() : ParseEngine.AUTO.name());
        try {
            documentVectorRepository.deleteByFileMd5(fileMd5);
            checkMemoryThreshold();

            String parsedMarkdown = minerUParseClient.parseToText(fileBytes, fileName);
            int savedChunks = saveParsedMarkdown(fileMd5, parsedMarkdown, userId, orgTag, isPublic, effectiveChunkSize);
            span.attribute("nexusmind.parse.saved_chunks", savedChunks)
                    .attribute("nexusmind.parse.engine", ParseEngine.MINERU.name());
            processingStatusService.markActualParseEngine(fileMd5, userId, ParseEngine.MINERU);
            logger.info("MinerU解析和入库完成，fileMd5: {}, chunks: {}", fileMd5, savedChunks);
            return savedChunks;
        } catch (Exception e) {
            span.error(e);
            if (shouldFallbackMinerUToTika(requestedEngine)) {
                logger.warn("MinerU解析失败，AUTO策略将回退到Tika，fileMd5: {}, fileName: {}, reason: {}",
                        fileMd5, fileName, e.getMessage());
                return parseWithTikaAndSave(fileMd5, new ByteArrayInputStream(fileBytes), userId, orgTag, isPublic, effectiveChunkSize);
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new RuntimeException("MinerU解析失败", e);
        } finally {
            span.end();
            span.close();
        }
    }

    private boolean shouldFallbackMinerUToTika(ParseEngine requestedEngine) {
        if (!minerUFallbackToTika) {
            return false;
        }
        ParseEngine engine = requestedEngine == null ? ParseEngine.AUTO : requestedEngine;
        return engine == ParseEngine.AUTO;
    }

    private int resolveChunkSize(Integer requestedChunkSize) {
        if (requestedChunkSize == null) {
            return chunkSize;
        }
        if (requestedChunkSize < minChunkSize || requestedChunkSize > maxChunkSize) {
            throw new IllegalArgumentException("chunkSize must be between " + minChunkSize + " and " + maxChunkSize);
        }
        return requestedChunkSize;
    }

    private ParseEngine resolveEngine(ParseEngine requestedEngine, String fileName) {
        ParseEngine engine = requestedEngine == null ? ParseEngine.AUTO : requestedEngine;
        if (engine == ParseEngine.AUTO) {
            return shouldUseMinerUByDefault(fileName) && minerUParseClient.isEnabled()
                    ? ParseEngine.MINERU
                    : ParseEngine.TIKA;
        }
        if (engine == ParseEngine.MINERU && !isMinerUSupportedFile(fileName)) {
            logger.warn("文件类型不支持MinerU解析，将使用Tika，fileName: {}", fileName);
            return ParseEngine.TIKA;
        }
        return engine;
    }

    private boolean shouldUseMinerUByDefault(String fileName) {
        String extension = getFileExtension(fileName);
        return "pdf".equalsIgnoreCase(extension);
    }

    private boolean isMinerUSupportedFile(String fileName) {
        String extension = getFileExtension(fileName);
        return switch (extension) {
            case "pdf", "png", "jpg", "jpeg", "bmp", "tif", "tiff", "webp", "docx", "pptx", "xlsx" -> true;
            default -> false;
        };
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * 兼容旧版本的解析方法
     */
    public int parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        // 使用默认值调用新方法
        return parseAndSave(fileMd5, fileStream, "unknown", "default", false);
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memoryUsage = (double) usedMemory / maxMemory;
        
        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();
            
            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " + 
                    String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }
    
    /**
     * 内部流式内容处理器，实现了父子文档切分策略的核心逻辑。
     * Tika解析器会调用characters方法，当累积的文本达到"父块"大小时，
     * 就触发processParentChunk方法，进行"子切片"的生成和入库。
     */
    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private final int effectiveChunkSize;
        private int savedChunkCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic, int effectiveChunkSize) {
            super(-1); // 禁用Tika的内部写入限制，我们自己管理缓冲区
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
            this.effectiveChunkSize = effectiveChunkSize;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= parentChunkSize) {
                processParentChunk();
            }
        }

        @Override
        public void endDocument() {
            // 处理文档末尾剩余的最后一部分内容
            if (buffer.length() > 0) {
                processParentChunk();
            }
        }

        private void processParentChunk() {
            String parentChunkText = buffer.toString();
            logger.debug("处理父文本块，大小: {} bytes", parentChunkText.length());

            // 1. 将父块分割成更小的、有语义的子切片
            List<String> childChunks = ParseService.this.splitTextIntoChunksWithSemantics(parentChunkText, effectiveChunkSize);

            // 2. 将子切片批量保存到数据库
            this.savedChunkCount = ParseService.this.saveChildChunks(fileMd5, childChunks, userId, orgTag,
                    isPublic, DocumentContentFormat.PLAIN_TEXT, this.savedChunkCount);

            // 3. 清空缓冲区，为下一个父块做准备
            buffer.setLength(0);
        }

        public int getSavedChunkCount() {
            return savedChunkCount;
        }
    }

    /**
     * 将子切片列表保存到数据库。
     *
     * @param fileMd5         文件的 MD5 哈希值
     * @param chunks          子切片文本列表
     * @param userId          上传用户ID
     * @param orgTag          组织标签
     * @param isPublic        是否公开
     * @param startingChunkId 当前批次的起始分片ID
     * @return 保存后总的分片数量
     */
    private int saveChildChunks(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic, DocumentContentFormat contentFormat, int startingChunkId) {
        int currentChunkId = startingChunkId;
        
        // 批量写入优化：先收集所有 DocumentVector 对象，再一次性批量保存
        List<DocumentVector> vectors = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            currentChunkId++;
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setContentFormat(contentFormat == null ? DocumentContentFormat.PLAIN_TEXT : contentFormat);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            vectors.add(vector);
        }
        documentVectorRepository.saveAll(vectors);
        
        // 原有逐个写入方式（已弃用，保留作为参考）：
        // for (String chunk : chunks) {
        //     currentChunkId++;
        //     var vector = new DocumentVector();
        //     vector.setFileMd5(fileMd5);
        //     vector.setChunkId(currentChunkId);
        //     vector.setTextContent(chunk);
        //     vector.setUserId(userId);
        //     vector.setOrgTag(orgTag);
        //     vector.setPublic(isPublic);
        //     documentVectorRepository.save(vector);
        // }
        
        logger.info("成功批量保存 {} 个子切片到数据库", chunks.size());
        return currentChunkId;
    }

    private int saveParsedMarkdown(String fileMd5, String parsedText, String userId, String orgTag, boolean isPublic, int effectiveChunkSize) {
        List<String> chunks = splitMarkdownIntoChunks(parsedText == null ? "" : parsedText, effectiveChunkSize);
        return saveChildChunks(fileMd5, chunks, userId, orgTag, isPublic, DocumentContentFormat.MARKDOWN, 0);
    }

    private int saveParsedText(String fileMd5, String parsedText, String userId, String orgTag, boolean isPublic) {
        List<String> chunks = splitTextIntoChunksWithSemantics(parsedText == null ? "" : parsedText, chunkSize);
        return saveChildChunks(fileMd5, chunks, userId, orgTag, isPublic, DocumentContentFormat.PLAIN_TEXT, 0);
    }

    /**
     * Markdown切片优先按块边界切分，避免把标题、表格、代码块拆得过碎。
     */
    private List<String> splitMarkdownIntoChunks(String markdown, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        List<String> blocks = splitMarkdownBlocks(markdown);
        StringBuilder currentChunk = new StringBuilder();
        String headingContext = "";

        for (String block : blocks) {
            String normalizedBlock = block.trim();
            if (normalizedBlock.isBlank()) {
                continue;
            }

            if (isMarkdownHeading(normalizedBlock)) {
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                headingContext = updateMarkdownHeadingContext(headingContext, normalizedBlock);
                currentChunk.append(normalizedBlock);
                continue;
            }

            String blockToAppend = currentChunk.isEmpty() && !headingContext.isBlank()
                    ? headingContext + "\n\n" + normalizedBlock
                    : normalizedBlock;

            if (currentChunk.length() + separatorLength(currentChunk) + blockToAppend.length() <= chunkSize) {
                appendMarkdownBlock(currentChunk, blockToAppend);
                continue;
            }

            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }

            if (blockToAppend.length() <= chunkSize) {
                currentChunk.append(blockToAppend);
            } else {
                chunks.addAll(splitLongMarkdownBlock(blockToAppend, headingContext, chunkSize));
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitMarkdownBlocks(String markdown) {
        List<String> blocks = new ArrayList<>();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder block = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                inCodeFence = !inCodeFence;
                appendLine(block, line);
                continue;
            }

            if (!inCodeFence && line.isBlank()) {
                if (!block.isEmpty()) {
                    blocks.add(block.toString());
                    block = new StringBuilder();
                }
                continue;
            }

            appendLine(block, line);
        }

        if (!block.isEmpty()) {
            blocks.add(block.toString());
        }
        return blocks;
    }

    private boolean isMarkdownHeading(String block) {
        return block.matches("(?s)^#{1,6}\\s+.+");
    }

    private String updateMarkdownHeadingContext(String currentContext, String heading) {
        int level = 0;
        while (level < heading.length() && heading.charAt(level) == '#') {
            level++;
        }

        List<String> headings = new ArrayList<>();
        if (currentContext != null && !currentContext.isBlank()) {
            headings.addAll(List.of(currentContext.split("\\n")));
        }
        int currentLevel = level;
        headings.removeIf(existing -> markdownHeadingLevel(existing) >= currentLevel);
        headings.add(heading.lines().findFirst().orElse(heading));
        return String.join("\n", headings);
    }

    private int markdownHeadingLevel(String heading) {
        int level = 0;
        while (level < heading.length() && heading.charAt(level) == '#') {
            level++;
        }
        return level == 0 ? Integer.MAX_VALUE : level;
    }

    private int separatorLength(StringBuilder builder) {
        return builder.isEmpty() ? 0 : 2;
    }

    private void appendMarkdownBlock(StringBuilder builder, String block) {
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(block);
    }

    private void appendLine(StringBuilder builder, String line) {
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private List<String> splitLongMarkdownBlock(String block, String headingContext, int chunkSize) {
        if (isMarkdownTable(block)) {
            return splitMarkdownTable(block, headingContext, chunkSize);
        }
        return splitLongParagraph(block, chunkSize);
    }

    private boolean isMarkdownTable(String block) {
        String[] lines = block.split("\\n");
        return lines.length >= 2 && lines[0].contains("|") && lines[1].matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*");
    }

    private List<String> splitMarkdownTable(String table, String headingContext, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] lines = table.split("\\n");
        String header = lines[0] + "\n" + lines[1];
        StringBuilder current = new StringBuilder();
        if (headingContext != null && !headingContext.isBlank()) {
            current.append(headingContext).append("\n\n");
        }
        current.append(header);

        for (int i = 2; i < lines.length; i++) {
            String nextLine = lines[i];
            if (current.length() + 1 + nextLine.length() > chunkSize && current.length() > header.length()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
                if (headingContext != null && !headingContext.isBlank()) {
                    current.append(headingContext).append("\n\n");
                }
                current.append(header);
            }
            current.append('\n').append(nextLine);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    /**
     * 智能文本分割，保持语义完整性
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按段落分割
        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            // 如果单个段落超过chunk大小，需要进一步分割
            if (paragraph.length() > chunkSize) {
                // 先保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 按句子分割长段落
                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize);
                chunks.addAll(sentenceChunks);
            }
            // 如果添加这个段落会超过chunk大小
            else if (currentChunk.length() + paragraph.length() > chunkSize) {
                // 保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }
                // 开始新chunk
                currentChunk = new StringBuilder(paragraph);
            }
            // 可以添加到当前chunk
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        // 添加最后一个chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 分割长段落，按句子边界
     */
    private List<String> splitLongParagraph(String paragraph, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 如果单个句子太长，按词分割
                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割
     */
    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        
        try {
            // 使用HanLP StandardTokenizer进行分词
            List<Term> termList = StandardTokenizer.segment(sentence);
            
            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;
                
                // 如果添加这个词会超过chunk大小限制，且当前chunk不为空
                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }
                
                currentChunk.append(word);
            }
            
            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
            }
            
            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}", 
                    sentence.length(), termList.size(), chunks.size());
                    
        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharacters(sentence, chunkSize);
         }
        
        return chunks;
    }
    
    /**
     * 备用方案：按字符分割
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
