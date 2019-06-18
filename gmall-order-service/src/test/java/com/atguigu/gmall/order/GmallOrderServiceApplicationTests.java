package com.atguigu.gmall.order;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RunWith(SpringRunner.class)
@SpringBootTest
public class GmallOrderServiceApplicationTests {

	@Test
	public void contextLoads() {
		Map<String, Object> map = new HashMap<>();
		map.put("aa","cc");

		ConcurrentHashMap<String, Object> concurrentHashMap = new ConcurrentHashMap<>();
		concurrentHashMap.put("aa","bb");

		// ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor();


	}

}
