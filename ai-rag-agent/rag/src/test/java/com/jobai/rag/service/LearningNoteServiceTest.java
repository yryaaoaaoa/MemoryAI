package com.jobai.rag.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.BusinessException;
import com.jobai.common.ErrorCode;
import com.jobai.knowledge.llm.LlmService;
import com.jobai.rag.entity.ChatMessage;
import com.jobai.rag.entity.ChatSession;
import com.jobai.rag.entity.LearningNote;
import com.jobai.rag.mapper.ChatSessionMapper;
import com.jobai.rag.mapper.LearningNoteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningNoteServiceTest {
    final LearningNoteMapper notes = mock(LearningNoteMapper.class);
    final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    final ChatSessionService chats = mock(ChatSessionService.class);
    final LlmService llm = mock(LlmService.class);
    final java.util.Map<String,LearningNote> committed = new java.util.HashMap<>();
    final LearningNoteService service = new LearningNoteService(notes, sessions, chats, llm);

    @BeforeEach void mappings() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), LearningNote.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), ChatSession.class);
        when(notes.findByCreateRequest(anyLong(), anyString())).thenAnswer(inv -> committed.get(inv.getArgument(0) + ":" + inv.getArgument(1)));
        when(notes.insertOnce(any())).thenAnswer(inv -> {
            LearningNote n = inv.getArgument(0); n.setId((long)committed.size()+1);
            committed.putIfAbsent(n.getUserId()+":"+n.getCreateRequestId(),n); return 1;
        });
        when(notes.updateIfVersion(any(),anyLong())).thenReturn(1);
    }

    ChatMessage message(long id, String role, String text) {
        ChatMessage m = new ChatMessage(); m.setId(id); m.setSessionId(10L); m.setRole(role); m.setContent(text); return m;
    }

    LearningNoteService.NoteInput input(Long sid, List<Long> ids) {
        return new LearningNoteService.NoteInput("事务与 ES", "为何不能回滚？", "数据库与 ES 是独立操作。", "PENDING", sid, ids, "10000000-0000-0000-0000-000000000001", 0L);
    }

    LearningNote stored() {
        LearningNote note = new LearningNote(); note.setId(5L); note.setUserId(1L); note.setVersion(0L);
        note.setTitle("事务"); note.setConfusion("困惑"); note.setContent("旧的理解"); note.setStatus("PENDING");
        note.setSourceSessionId(10L); note.setSourceMessageIds("[1]");
        note.setSourceSnapshot("[{\"id\":1,\"sessionId\":10,\"role\":\"user\",\"content\":\"为什么\"}]");
        when(notes.selectById(5L)).thenReturn(note); return note;
    }

    @Test void savesOrderedAuthoritativeSnapshotWithoutCallingModel() throws Exception {
        when(chats.getMessages(10L, 1L)).thenReturn(List.of(message(1,"user","为什么？"), message(2,"assistant","因为独立操作。")));
        LearningNote created = service.create(1L, input(10L, List.of(2L,1L)));
        var snapshot = new ObjectMapper().readTree(created.getSourceSnapshot());
        assertEquals(1L, snapshot.get(0).get("id").asLong());
        assertEquals("因为独立操作。", snapshot.get(1).get("content").asText());
        assertEquals(1L, created.getUserId());
        verify(notes).insertOnce(created); verifyNoInteractions(llm);
    }

    @Test void foreignSessionIsRejectedBeforeSavingOrDrafting() {
        when(chats.getMessages(10L, 2L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertEquals(403, assertThrows(BusinessException.class, () -> service.create(2L, input(10L,List.of(1L)))).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.draft(2L, new LearningNoteService.DraftInput(10L,List.of(1L),null,null))).getCode());
        verify(notes,never()).insertOnce(any()); verifyNoInteractions(llm);
    }

    @Test void messageIdsFromOtherSessionsAndInternalToolMessagesAreRejected() {
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","why"),message(2,"tool","secret")));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L,input(10L,List.of(3L))));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L,input(10L,List.of(2L))));
        ChatMessage hidden = message(3,"assistant","internal"); hidden.setMetadata("{\"tool_calls\":[]}");
        when(chats.getMessages(10L,1L)).thenReturn(List.of(hidden));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L,input(10L,List.of(3L))));
        verify(notes, never()).insertOnce(any(LearningNote.class));
    }

    @Test void invalidInputDoesNotPersist() {
        assertThrows(IllegalArgumentException.class, () -> service.create(1L, input(10L, List.of(1L,1L))));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L,
                new LearningNoteService.NoteInput(" ", "", "text", "PENDING",10L,List.of(1L), "10000000-0000-0000-0000-000000000001",0L)));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L,
                new LearningNoteService.NoteInput("title", "", "text", "FAKE",10L,List.of(1L), "10000000-0000-0000-0000-000000000001",0L)));
        verifyNoInteractions(chats); verify(notes,never()).insertOnce(any());
    }

    @Test void allExistingNoteOperationsRejectOtherOwners() {
        stored();
        assertEquals(403, assertThrows(BusinessException.class, () -> service.get(5L,2L)).getCode());
        assertThrows(BusinessException.class, () -> service.update(5L,2L,input(null,null)));
        assertThrows(BusinessException.class, () -> service.delete(5L,2L));
        assertThrows(BusinessException.class, () -> service.createSession(5L,2L));
        assertThrows(BusinessException.class, () -> service.openTree(5L,2L));
        assertThrows(BusinessException.class, () -> service.draft(2L,new LearningNoteService.DraftInput(10L,List.of(1L),5L,null)));
        verifyNoInteractions(sessions, chats, llm);
        verify(notes, never()).updateById(any(LearningNote.class));
        verify(notes, never()).deleteById(anyLong());
    }

    @Test void editingAfterSourceSessionDeletionPreservesSnapshot() {
        LearningNote note = stored(); String snapshot = note.getSourceSnapshot();
        LearningNote edited = service.update(5L,1L,input(null,null));
        assertEquals(snapshot, edited.getSourceSnapshot());
        assertEquals("数据库与 ES 是独立操作。",edited.getContent());
        verifyNoInteractions(chats); verify(notes).updateIfVersion(edited,0L);
    }

    @Test void updatingWithNewDiscussionAppendsSnapshotWithoutDuplicatingOriginal() throws Exception {
        stored();
        when(chats.getMessages(11L,1L)).thenReturn(List.of(message(2,"user","然后呢"),message(3,"assistant","补偿处理")));
        LearningNote updated = service.update(5L,1L,input(11L,List.of(2L,3L)));
        var snapshot = new ObjectMapper().readTree(updated.getSourceSnapshot());
        assertEquals(3,snapshot.size()); assertEquals(10L,updated.getSourceSessionId());
        assertEquals(11L,snapshot.get(2).get("sessionId").asLong());
        service.update(5L,1L,new LearningNoteService.NoteInput("事务与 ES", "为何不能回滚？", "数据库与 ES 是独立操作。", "PENDING",11L,List.of(2L,3L),null,1L));
        assertEquals(3,new ObjectMapper().readTree(updated.getSourceSnapshot()).size());
    }

    @Test void noteSessionsUseCurrentNoteAndDeletionDetachesWithoutDeletingChat() {
        LearningNote note = stored();
        doAnswer(inv -> { ((ChatSession)inv.getArgument(0)).setId(12L); return 1; }).when(sessions).insert(any(ChatSession.class));
        assertEquals(12L,service.createSession(5L,1L));
        var captured = ArgumentCaptor.forClass(ChatSession.class); verify(sessions).insert(captured.capture());
        assertEquals(5L,captured.getValue().getNoteId());
        when(sessions.selectById(12L)).thenReturn(captured.getValue());
        note.setContent("补充后的理解");
        assertTrue(service.contextForSession(12L,1L).contains("补充后的理解"));
        assertThrows(BusinessException.class, () -> service.contextForSession(12L,2L));
        service.delete(5L,1L);
        verify(sessions).update(isNull(),any()); verify(notes).deleteById(5L);
        verify(sessions,never()).deleteById(anyLong());
        when(notes.selectById(5L)).thenReturn(null);
        assertEquals("",service.contextForSession(12L,1L));
    }

    @Test void openingTreeReusesLatestOwnedSessionWithoutChangingNoteOrCallingModel() {
        LearningNote note = stored();
        ChatSession existing = new ChatSession(); existing.setId(12L); existing.setNoteId(5L); existing.setUserId(1L);
        when(sessions.selectOne(any())).thenReturn(existing);
        assertEquals(12L, service.openTree(5L,1L));
        assertEquals(12L, service.openTree(5L,1L));
        var query = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(sessions,times(2)).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("note_id"));
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertTrue(query.getValue().getSqlSegment().contains("updated_at DESC"));
        verify(sessions,never()).insert(any(ChatSession.class));
        verify(notes,never()).updateIfVersion(any(),anyLong());
        verifyNoInteractions(llm,chats);
        assertNotNull(note.getSourceSnapshot());
    }

    @Test void openingTreeAfterSessionDeletionCreatesOnlyALinkedSession() {
        LearningNote note = stored();
        String snapshot = note.getSourceSnapshot();
        doAnswer(inv -> { ((ChatSession)inv.getArgument(0)).setId(12L); return 1; }).when(sessions).insert(any(ChatSession.class));
        assertEquals(12L, service.openTree(5L,1L));
        var created = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessions).insert(created.capture());
        assertEquals(1L,created.getValue().getUserId());
        assertEquals(5L,created.getValue().getNoteId());
        assertEquals(snapshot,note.getSourceSnapshot());
        verifyNoInteractions(llm,chats);
    }

    @Test void failedDraftDoesNotModifyExistingNoteOrPreventRawSave() {
        LearningNote note = stored();
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","why")));
        when(llm.chat(anyString(),anyString())).thenThrow(new RuntimeException("model unavailable"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.draft(1L,new LearningNoteService.DraftInput(10L,List.of(1L),5L,null)));
        assertEquals(503,error.getCode()); assertEquals("旧的理解",note.getContent());
        verify(notes,never()).updateById(any(LearningNote.class));
        LearningNote raw = service.create(1L,input(10L,List.of(1L)));
        assertNotNull(raw.getSourceSnapshot());
    }

    @Test void validDraftIsReturnedWithoutPersistenceAndMalformedOutputIsRejected() {
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","why")));
        when(llm.chat(anyString(),anyString())).thenReturn("```json\n{\"title\":\"事务\",\"confusion\":\"边界\",\"content\":\"解释\"}\n```", "{\"title\":{},\"content\":\"text\"}");
        assertEquals("解释", service.draft(1L,new LearningNoteService.DraftInput(10L,List.of(1L),null,null)).content());
        assertThrows(BusinessException.class, () -> service.draft(1L,new LearningNoteService.DraftInput(10L,List.of(1L),null,null)));
        verifyNoInteractions(notes);
    }

    @Test void oversizedDraftIsRejectedButSourceCanStillBeSavedWithoutModel() {
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"assistant","x".repeat(40001))));
        assertThrows(IllegalArgumentException.class,
                () -> service.draft(1L,new LearningNoteService.DraftInput(10L,List.of(1L),null,null)));
        assertNotNull(service.create(1L,input(10L,List.of(1L))).getSourceSnapshot());
        verifyNoInteractions(llm);
    }

    @Test void listIsPaginatedAndAlwaysScopedToOwner() {
        when(notes.selectCount(any())).thenAnswer(inv -> {
            var filter = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) inv.getArgument(0);
            assertFalse(filter.getSqlSegment().contains("LIMIT"));
            assertTrue(filter.getParamNameValuePairs().containsValue(7L));
            assertTrue(filter.getParamNameValuePairs().containsValue("REVIEW"));
            assertTrue(filter.getParamNameValuePairs().containsValue("%ES%"));
            return 23L;
        });
        LearningNote selected = stored();
        when(notes.selectList(any())).thenReturn(List.of(selected));
        var result = service.list(7L,"ES","REVIEW",2,10);
        assertEquals(2,result.page()); assertEquals(10,result.size()); assertEquals(23,result.total());
        assertEquals(List.of(selected), result.records());
        var filter = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(notes).selectList(filter.capture());
        assertTrue(filter.getValue().getSqlSegment().contains("user_id"));
        assertTrue(filter.getValue().getSqlSegment().endsWith("LIMIT 10 OFFSET 10"));
        assertTrue(filter.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(filter.getValue().getParamNameValuePairs().containsValue("REVIEW"));
        assertTrue(filter.getValue().getParamNameValuePairs().containsValue("%ES%"));
        assertThrows(IllegalArgumentException.class, () -> service.list(7L,"",null,0,10));
    }

    @Test void pagePastLastRetainsTotalAndUsesLongOffset() {
        when(notes.selectCount(any())).thenReturn(3L);
        when(notes.selectList(any())).thenReturn(List.of());
        var result = service.list(1L,null,null,Integer.MAX_VALUE,100);
        assertEquals(3,result.total()); assertTrue(result.records().isEmpty());
        var filter = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(notes).selectList(filter.capture());
        assertTrue(filter.getValue().getSqlSegment().endsWith("LIMIT 100 OFFSET 214748364600"));
    }

    @Test void lostCreationResponseCanBeRetriedWithoutInsertingAgainEvenAfterSourceDeletion() {
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","why")));
        var payload = input(10L,List.of(1L));
        LearningNote first = service.create(1L,payload);
        when(chats.getMessages(10L,1L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        LearningNote retry = service.create(1L,payload);
        assertEquals(first.getId(),retry.getId());
        assertEquals(0L,retry.getVersion());
        verify(notes,times(1)).insertOnce(any());
        verify(chats,times(1)).getMessages(10L,1L);
    }

    @Test void repeatedRequestWithDifferentPayloadIsConflictAndDoesNotDiscardSavedBody() {
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","why")));
        var payload = input(10L,List.of(1L));
        LearningNote first = service.create(1L,payload);
        var changed = new LearningNoteService.NoteInput(payload.title(),payload.confusion(),"改过的草稿",
                payload.status(),payload.sourceSessionId(),payload.sourceMessageIds(),payload.requestId(),null);
        assertEquals(409,assertThrows(BusinessException.class,()->service.create(1L,changed)).getCode());
        assertEquals(payload.content(),first.getContent());
        verify(notes,times(1)).insertOnce(any());
    }

    @Test void identicalRequestIdsRemainScopedToDifferentOwners() {
        when(chats.getMessages(eq(10L),anyLong())).thenReturn(List.of(message(1,"user","why")));
        LearningNote first = service.create(1L,input(10L,List.of(1L)));
        LearningNote second = service.create(2L,input(10L,List.of(1L)));
        assertNotEquals(first.getId(),second.getId());
        assertEquals(1L,first.getUserId()); assertEquals(2L,second.getUserId());
        assertEquals(2,committed.size());
    }

    @Test void staleVersionIsRejectedBeforeApplyingContentOrSourceChanges() {
        LearningNote latest = stored(); latest.setVersion(2L); latest.setContent("另一页刚保存的理解");
        assertEquals(409,assertThrows(BusinessException.class,()->service.update(5L,1L,input(10L,List.of(1L)))).getCode());
        assertEquals("另一页刚保存的理解",latest.getContent());
        verify(notes,never()).updateIfVersion(any(),anyLong()); verifyNoInteractions(chats);
    }

    @Test void atomicVersionUpdateRejectsChangesMadeAfterInitialRead() {
        stored();
        when(notes.updateIfVersion(any(),eq(0L))).thenReturn(0);
        assertEquals(409,assertThrows(BusinessException.class,()->service.update(5L,1L,input(null,null))).getCode());
        verify(notes).updateIfVersion(any(),eq(0L));
    }

    @Test void updateNeedsVersionAndSuccessfulUpdateReturnsIncrementedVersion() {
        stored();
        var noVersion = new LearningNoteService.NoteInput("标题","","正文","PENDING",null,null,null,null);
        assertThrows(IllegalArgumentException.class,()->service.update(5L,1L,noVersion));
        LearningNote updated=service.update(5L,1L,input(null,null));
        assertEquals(1L,updated.getVersion());
        verify(notes).updateIfVersion(updated,0L);
    }

    @Test void currentEditorDraftReplacesOldSavedDraftInModelInputWithoutWriting() {
        stored();
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","来源问题")));
        when(llm.chat(anyString(),anyString())).thenReturn("{\"title\":\"新标题\",\"confusion\":\"新困惑\",\"content\":\"整理后理解\"}");
        var current=new LearningNoteService.Draft("我的标题","我补充的困惑","尚未保存的业务流水号例子");
        var result=service.draft(1L,new LearningNoteService.DraftInput(10L,List.of(1L),5L,current));
        assertEquals("整理后理解",result.content());
        var prompt=ArgumentCaptor.forClass(String.class); verify(llm).chat(anyString(),prompt.capture());
        assertTrue(prompt.getValue().contains("尚未保存的业务流水号例子"));
        assertTrue(prompt.getValue().contains("我补充的困惑"));
        assertFalse(prompt.getValue().contains("旧的理解"));
        verify(notes,never()).updateIfVersion(any(),anyLong()); verify(notes,never()).insertOnce(any());
    }

    @Test void bodyModeUsesOnlyCurrentDraftAndAcceptsFortyThousandCharacters() {
        String body = "原文".repeat(20000);
        when(llm.chat(anyString(),anyString())).thenReturn("{\"title\":\"整理标题\",\"confusion\":\"困惑\",\"content\":\"整理结果\"}");
        var current = new LearningNoteService.Draft("编辑后的标题","我的困惑",body);
        assertEquals("整理结果",service.draft(1L,new LearningNoteService.DraftInput(999L,List.of(999L),null,current,"BODY")).content());
        var prompt = ArgumentCaptor.forClass(String.class); verify(llm).chat(anyString(),prompt.capture());
        assertTrue(prompt.getValue().contains(body));
        assertTrue(prompt.getValue().contains("编辑后的标题"));
        assertTrue(prompt.getValue().contains("我的困惑"));
        assertFalse(prompt.getValue().contains("所选问答："));
        assertEquals(prompt.getValue().indexOf(body),prompt.getValue().lastIndexOf(body));
        verifyNoInteractions(chats,notes);
    }

    @Test void bodyModeRequiresEditableContentAndStillChecksNoteOwnership() {
        for (var current : java.util.Arrays.asList(null,
                new LearningNoteService.Draft("标题",""," "),
                new LearningNoteService.Draft("标题","","x".repeat(40001)),
                new LearningNoteService.Draft(null,"","正文"),
                new LearningNoteService.Draft("标题",null,"正文"))) {
            assertThrows(IllegalArgumentException.class,()->service.draft(1L,
                    new LearningNoteService.DraftInput(null,null,null,current,"BODY")));
        }
        stored();
        assertEquals(403,assertThrows(BusinessException.class,()->service.draft(2L,
                new LearningNoteService.DraftInput(null,null,5L,new LearningNoteService.Draft("标题","","正文"),"BODY"))).getCode());
        verifyNoInteractions(chats,llm);
    }

    @Test void invalidModeAndOversizedMergeSelectionNeverReadSourcesOrCallModel() {
        var current = new LearningNoteService.Draft("标题","","正文");
        assertThrows(IllegalArgumentException.class,()->service.draft(1L,
                new LearningNoteService.DraftInput(10L,List.of(1L),null,current,"UNKNOWN")));
        var tooMany = java.util.stream.LongStream.rangeClosed(1,41).boxed().toList();
        for (var ids : java.util.Arrays.asList(null,List.<Long>of(),tooMany)) {
            assertThrows(IllegalArgumentException.class,()->service.draft(1L,
                    new LearningNoteService.DraftInput(10L,ids,null,current,"MERGE")));
        }
        verifyNoInteractions(chats,llm);
    }

    @Test void copiedLongBodyCanBeOrganizedWithoutReattachingSources() {
        String answer = "长答案标记"+"长".repeat(24000);
        String body = "### 回答\n\n"+answer+"\n\n手写尾注。";
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"assistant",answer)));
        var current = new LearningNoteService.Draft("标题","困惑",body);
        var error = assertThrows(IllegalArgumentException.class,()->service.draft(1L,
                new LearningNoteService.DraftInput(10L,List.of(1L),null,current,"MERGE")));
        assertTrue(error.getMessage().contains("整理正文"));
        verifyNoInteractions(llm);
        when(llm.chat(anyString(),anyString())).thenReturn("{\"title\":\"标题\",\"confusion\":\"\",\"content\":\"结果\"}");
        assertEquals("结果",service.draft(1L,new LearningNoteService.DraftInput(null,null,null,current,"BODY")).content());
        verify(chats,times(1)).getMessages(10L,1L);
    }

    @Test void mergeModeIncludesSelectedSourcesEvenWhenShortOrSimilarTextIsAlreadyInBody() throws Exception {
        var mapper = new ObjectMapper();
        for (String body : List.of("另一问题的回答是不可以。", "### 问题\n\n允许缓存吗？\n\n### 回答\n\n可以。\n\n但不能存敏感数据。",
                "### 问题\n\n允许缓存吗？\n\n### 回答\n\n可以。")) {
            reset(llm);
            when(chats.getMessages(10L,1L)).thenReturn(List.of(message(3,"user","允许缓存吗？"),message(4,"assistant","可以。")));
            when(llm.chat(anyString(),anyString())).thenReturn("{\"title\":\"标题\",\"confusion\":\"\",\"content\":\"结果\"}");
            service.draft(1L,new LearningNoteService.DraftInput(10L,List.of(4L,3L),null,new LearningNoteService.Draft("标题","",body),"MERGE"));
            var prompt = ArgumentCaptor.forClass(String.class); verify(llm).chat(anyString(),prompt.capture());
            assertTrue(prompt.getValue().contains(body));
            var selected = mapper.readTree(prompt.getValue().split("所选问答：\\n",2)[1]);
            assertEquals(2,selected.size()); assertEquals(3,selected.get(0).get("id").asInt());
            assertEquals(4,selected.get(1).get("id").asInt()); assertEquals("可以。",selected.get(1).get("content").asText());
        }
        verifyNoInteractions(notes);
    }

    @Test void existingNoteRemainsMergeContextWhenLegacyCurrentDraftAndModeAreAbsent() {
        LearningNote note = stored();
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","why")));
        when(llm.chat(anyString(),anyString())).thenReturn("{\"title\":\"标题\",\"confusion\":\"\",\"content\":\"结果\"}");
        service.draft(1L,new LearningNoteService.DraftInput(10L,List.of(1L),5L,null,null));
        var prompt = ArgumentCaptor.forClass(String.class); verify(llm).chat(anyString(),prompt.capture());
        assertTrue(prompt.getValue().contains(note.getContent()));
        assertTrue(prompt.getValue().contains("所选问答："));
        verify(notes,never()).updateIfVersion(any(),anyLong());
    }

    @Test void capturedSourcesFromMultipleBatchesCanExceedOneOperationSelectionLimit() throws Exception {
        List<ChatMessage> captured = java.util.stream.LongStream.rangeClosed(1,80)
                .mapToObj(id -> message(id,id%2==1?"user":"assistant","原文"+id)).toList();
        when(chats.getMessages(10L,1L)).thenReturn(captured);
        var created = service.create(1L,input(10L,captured.stream().map(ChatMessage::getId).toList()));
        var snapshot = new ObjectMapper().readTree(created.getSourceSnapshot());
        assertEquals(80,snapshot.size()); assertEquals("原文80",snapshot.get(79).get("content").asText());
        verifyNoInteractions(llm);
    }

    @Test void freeEditingAndDifferentSelectionNeverPruneOrRewriteSavedOriginals() throws Exception {
        LearningNote note = stored();
        when(chats.getMessages(11L,1L)).thenReturn(List.of(message(1,"user","changed upstream text"),message(2,"assistant","新来源原文")));
        var updated = service.update(5L,1L,new LearningNoteService.NoteInput("新标题","","完全重新写的正文😀","REVIEW",11L,List.of(1L,2L),null,0L));
        var snapshot = new ObjectMapper().readTree(updated.getSourceSnapshot());
        assertEquals(2,snapshot.size()); assertEquals("为什么",snapshot.get(0).get("content").asText());
        assertEquals("新来源原文",snapshot.get(1).get("content").asText());
        String captured = updated.getSourceSnapshot();
        updated = service.update(5L,1L,new LearningNoteService.NoteInput("新标题","","再次改写","REVIEW",null,null,null,1L));
        assertEquals(captured,updated.getSourceSnapshot()); assertEquals("再次改写",updated.getContent());
        verify(chats,times(1)).getMessages(anyLong(),anyLong());
    }

    @Test void sourceArchiveLimitStillProtectsCreateAndAccumulatedUpdates() {
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"assistant","x".repeat(120001))));
        assertThrows(IllegalArgumentException.class,()->service.create(1L,input(10L,List.of(1L))));
        verify(notes,never()).insertOnce(any());
        stored();
        when(chats.getMessages(11L,1L)).thenReturn(List.of(message(2,"assistant","x".repeat(120000))));
        assertThrows(IllegalArgumentException.class,()->service.update(5L,1L,input(11L,List.of(2L))));
        verify(notes,never()).updateIfVersion(any(),anyLong());
    }

    @Test void bodyModelFailureDoesNotWriteOrDiscardSavedNote() {
        LearningNote note = stored(); String sourceSnapshot = note.getSourceSnapshot();
        when(llm.chat(anyString(),anyString())).thenThrow(new IllegalStateException("unavailable"));
        var current = new LearningNoteService.Draft("编辑标题","我的困惑","未保存的手写内容");
        assertEquals(503,assertThrows(BusinessException.class,()->service.draft(1L,
                new LearningNoteService.DraftInput(null,null,5L,current,"BODY"))).getCode());
        assertEquals("旧的理解",note.getContent()); assertEquals(sourceSnapshot,note.getSourceSnapshot());
        verifyNoInteractions(chats); verify(notes,never()).updateIfVersion(any(),anyLong());
    }

    private static final String TREE = """
            {"version":1,"nodes":[{"id":"root","title":"线程池","body":"解释 **队列**。","children":[
              {"id":"child","title":"示例","body":"```java\\nexecute(task);\\n```","children":[]}]}]}
            """;

    @Test void treeCreationDerivesSearchableContentAndReplaysCanonicalTree() throws Exception {
        when(chats.getMessages(10L,1L)).thenReturn(List.of(message(1,"user","为什么？")));
        var input = new LearningNoteService.NoteInput("笔记","困惑","不可信的旧正文","REVIEW",10L,List.of(1L),
                "10000000-0000-0000-0000-000000000001",null,TREE);
        var created = service.create(1L,input);
        assertEquals("## 线程池\n\n解释 **队列**。\n\n### 示例\n\n```java\nexecute(task);\n```\n",created.getContent());
        var tree = new ObjectMapper().readTree(created.getTreeJson());
        assertEquals("child",tree.at("/nodes/0/children/0/id").asText());
        assertEquals("为什么？",new ObjectMapper().readTree(created.getSourceSnapshot()).get(0).get("content").asText());
        var compact = new LearningNoteService.NoteInput(input.title(),input.confusion(),"另一份旧正文",input.status(),
                input.sourceSessionId(),input.sourceMessageIds(),input.requestId(),null,created.getTreeJson());
        assertSame(created,service.create(1L,compact));
        var changedId = new LearningNoteService.NoteInput(input.title(),input.confusion(),input.content(),input.status(),
                input.sourceSessionId(),input.sourceMessageIds(),input.requestId(),null,TREE.replace("\"child\"","\"other\""));
        assertEquals(409,assertThrows(BusinessException.class,()->service.create(1L,changedId)).getCode());
        verify(notes,times(1)).insertOnce(any());
    }

    @Test void treeUpdatePersistsHierarchyAndCurrentMarkdownTogetherWithoutChangingSources() {
        LearningNote original = stored(); String sources = original.getSourceSnapshot();
        var updated = service.update(5L,1L,new LearningNoteService.NoteInput("笔记","困惑",null,"REVIEW",null,null,null,0L,TREE));
        assertEquals(1L,updated.getVersion()); assertEquals(sources,updated.getSourceSnapshot());
        assertTrue(updated.getTreeJson().contains("child")); assertTrue(updated.getContent().contains("### 示例"));
        ChatSession session = new ChatSession(); session.setId(10L); session.setUserId(1L); session.setNoteId(5L);
        when(sessions.selectById(10L)).thenReturn(session);
        assertTrue(service.contextForSession(10L,1L).contains("execute(task);"));
        verify(notes).updateIfVersion(updated,0L); verifyNoInteractions(chats,llm);
    }

    @Test void legacyClientKeepsTreeForMetadataOnlyAndClearsItWhenChangingBody() {
        LearningNote original = stored(); original.setTreeJson(TREE);
        String body = original.getContent();
        var updated = service.update(5L,1L,new LearningNoteService.NoteInput("改名","",body,"UNDERSTOOD",null,null,null,0L));
        assertEquals(TREE,updated.getTreeJson());
        updated = service.update(5L,1L,new LearningNoteService.NoteInput("改名","","新纯文本正文","UNDERSTOOD",null,null,null,1L));
        assertNull(updated.getTreeJson()); assertEquals("新纯文本正文",updated.getContent());
        verify(notes,times(2)).updateIfVersion(any(),anyLong());
    }

    @Test void malformedTreesAndOverlongGeneratedBodyNeverPersist() {
        for (String tree : List.of("not JSON", "{}", "{\"version\":2,\"nodes\":[]}", "{\"version\":1,\"nodes\":[]}",
                TREE.replace("\"child\"","\"root\""), TREE.replace("\"children\":[]","\"children\":null"),
                TREE.replace("\"示例\"","\" \""), TREE.replace("线程池","x".repeat(121)),
                TREE.replace("解释 **队列**。","x".repeat(40000)))) {
            assertThrows(IllegalArgumentException.class,()->service.create(1L,
                    new LearningNoteService.NoteInput("笔记","","ignored","REVIEW",10L,List.of(1L),
                            "10000000-0000-0000-0000-000000000001",null,tree)));
        }
        verify(notes,never()).insertOnce(any()); verifyNoInteractions(chats,llm);
    }

    @Test void treeUpdatesStillRejectWrongOwnerAndStaleVersionBeforeMutation() {
        LearningNote original = stored();
        var input = new LearningNoteService.NoteInput("笔记","","ignored","REVIEW",null,null,null,0L,TREE);
        assertEquals(403,assertThrows(BusinessException.class,()->service.update(5L,2L,input)).getCode());
        original.setVersion(1L);
        assertEquals(409,assertThrows(BusinessException.class,()->service.update(5L,1L,input)).getCode());
        assertNull(original.getTreeJson()); assertEquals("旧的理解",original.getContent());
        verify(notes,never()).updateIfVersion(any(),anyLong());
    }
}
