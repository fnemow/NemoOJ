#pragma once
#include <cstdint>
struct ListNode {
    int32_t val; ListNode* next;
    ListNode(int32_t value=0, ListNode* tail=nullptr): val(value), next(tail) {}
};
struct TreeNode {
    int32_t val; TreeNode *left, *right;
    TreeNode(int32_t value=0, TreeNode* l=nullptr, TreeNode* r=nullptr): val(value), left(l), right(r) {}
};
