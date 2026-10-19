package com.nemo.OJbackendquestionservice.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StudyPlanCatalogTest {
    StudyPlanCatalog catalog(){var c=spy(new StudyPlanCatalog(mock(JdbcTemplate.class),new ObjectMapper()));doReturn(List.of(
        Map.of("problemNo",1L,"tags",List.of("链表","简单")),Map.of("problemNo",2L,"tags",List.of("二叉树","中等")),Map.of("problemNo",3L,"tags",List.of("链表","二叉树")),Map.of("problemNo",4L,"tags",List.of("数学")))).when(c).online();doReturn(Set.of(1L)).when(c).accepted(7L);return c;}
    @Test void unionCandidatesExcludeAcceptedAndUnrelated(){assertEquals(List.of(2L,3L),catalog().candidates(7L,List.of("链表","二叉树"),false,2).stream().map(p->p.get("problemNo")).toList());}
    @Test void reviewExplicitlyIncludesAccepted(){assertEquals(3,catalog().candidates(7L,List.of("链表","二叉树"),true,3).size());}
    @Test void unknownDifficultyAndShortageFailBeforeModelTasks(){assertThrows(BusinessException.class,()->catalog().candidates(7L,List.of("红黑树"),false,7));assertThrows(BusinessException.class,()->catalog().candidates(7L,List.of("简单"),true,1));assertThrows(BusinessException.class,()->catalog().candidates(7L,List.of("链表"),false,7));}
    @Test void topicsDescribeWholeCatalogAndReviewCounts(){var topics=catalog().topics(7L,false);var linked=topics.stream().filter(t->"链表".equals(t.get("tag"))).findFirst().orElseThrow();assertEquals(2,linked.get("total"));assertEquals(1,linked.get("available"));assertTrue(topics.stream().noneMatch(t->"简单".equals(t.get("tag"))));}
}
