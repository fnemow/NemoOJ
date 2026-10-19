#pragma once

#include <cstdint>
#include <cstddef>
#include <initializer_list>
#include <istream>
#include <iterator>
#include <string>
#include <type_traits>
#include <utility>

// Experimental ABI: only this small declaration surface is compiled with
// untrusted solution code. The nlohmann implementation is compiled in the
// trusted runtime image and linked fresh for every submission.
namespace nemo_lite {
namespace api {
using Handle = void*;
Handle null_value();
Handle boolean(bool);
Handle signed_integer(int64_t);
Handle unsigned_integer(uint64_t);
Handle floating(double);
Handle text(const std::string&);
Handle object();
Handle array();
Handle parse(const std::string&);
Handle clone(Handle);
void destroy(Handle) noexcept;
bool is_null(Handle);
bool is_object(Handle);
bool is_array(Handle);
bool is_boolean(Handle);
bool is_integer(Handle);
bool is_unsigned(Handle);
bool is_number(Handle);
bool is_string(Handle);
int64_t get_signed(Handle);
uint64_t get_unsigned(Handle);
double get_double(Handle);
bool get_boolean(Handle);
std::string get_string(Handle);
size_t size(Handle);
Handle at_key(Handle, const std::string&);
Handle at_index(Handle, size_t);
void push(Handle, Handle);
void set(Handle, const std::string&, Handle);
void erase_last(Handle);
std::string dump(Handle);
}

class json {
    api::Handle handle_;
    explicit json(api::Handle handle, int) : handle_(handle) {}
public:
    json() : handle_(api::null_value()) {}
    json(std::nullptr_t) : json() {}
    json(bool value) : handle_(api::boolean(value)) {}
    json(int value) : handle_(api::signed_integer(value)) {}
    json(int64_t value) : handle_(api::signed_integer(value)) {}
    json(uint64_t value) : handle_(api::unsigned_integer(value)) {}
    json(double value) : handle_(api::floating(value)) {}
    json(const char* value) : handle_(api::text(value)) {}
    json(const std::string& value) : handle_(api::text(value)) {}
    json(std::initializer_list<std::pair<std::string,json>> fields) : handle_(api::object()) {
        for (const auto& field : fields) api::set(handle_, field.first, field.second.handle_);
    }
    json(const json& other) : handle_(api::clone(other.handle_)) {}
    json(json&& other) noexcept : handle_(other.handle_) { other.handle_ = nullptr; }
    ~json() { if (handle_) api::destroy(handle_); }
    json& operator=(json other) noexcept { std::swap(handle_, other.handle_); return *this; }

    static json parse(std::istream& input) {
        return json(api::parse(std::string(std::istreambuf_iterator<char>(input),
                                           std::istreambuf_iterator<char>())), 0);
    }
    static json array(std::initializer_list<json> values = {}) {
        json out(api::array(), 0);
        for (const auto& value : values) api::push(out.handle_, value.handle_);
        return out;
    }
    bool is_null() const { return api::is_null(handle_); }
    bool is_object() const { return api::is_object(handle_); }
    bool is_array() const { return api::is_array(handle_); }
    bool is_boolean() const { return api::is_boolean(handle_); }
    bool is_number_integer() const { return api::is_integer(handle_); }
    bool is_number_unsigned() const { return api::is_unsigned(handle_); }
    bool is_number() const { return api::is_number(handle_); }
    bool is_string() const { return api::is_string(handle_); }
    size_t size() const { return api::size(handle_); }
    bool empty() const { return size() == 0; }
    json at(const std::string& key) const { return json(api::at_key(handle_, key), 0); }
    json operator[](size_t index) const { return json(api::at_index(handle_, index), 0); }
    json back() const { return (*this)[size()-1]; }
    void erase(size_t index) { if (index == size()-1) api::erase_last(handle_); }
    void push_back(const json& value) { api::push(handle_, value.handle_); }
    std::string dump() const { return api::dump(handle_); }

    template<class T> T get() const {
        if constexpr (std::is_same_v<T,bool>) return api::get_boolean(handle_);
        else if constexpr (std::is_same_v<T,std::string>) return api::get_string(handle_);
        else if constexpr (std::is_integral_v<T> && std::is_unsigned_v<T>) return static_cast<T>(api::get_unsigned(handle_));
        else if constexpr (std::is_integral_v<T>) return static_cast<T>(api::get_signed(handle_));
        else return static_cast<T>(api::get_double(handle_));
    }
    struct iterator {
        const json* owner;
        size_t index;
        json operator*() const { return (*owner)[index]; }
        iterator& operator++() { ++index; return *this; }
        bool operator!=(const iterator& other) const { return index != other.index; }
    };
    iterator begin() const { return {this,0}; }
    iterator end() const { return {this,size()}; }
};
}
